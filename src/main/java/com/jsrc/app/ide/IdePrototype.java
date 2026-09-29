package com.jsrc.app.ide;

import com.jsrc.app.engine.CallersResult;
import com.jsrc.app.engine.JsrcEngine;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/** Small local editor prototype backed exclusively by the in-process engine. */
public final class IdePrototype {
    private final IdeProject project;
    private final JsrcEngine engine = new JsrcEngine();
    private final JFrame frame = new JFrame("jsrc IDE prototype");
    private final JTextField query = new JTextField();
    private final DefaultListModel<NavigationItem> results = new DefaultListModel<>();
    private final JList<NavigationItem> resultList = new JList<>(results);
    private final JTextArea source = new JTextArea();
    private final JLabel status = new JLabel("Search text or enter Class.method");
    private final List<JButton> actions = new ArrayList<>();

    private IdePrototype(IdeProject project) {
        this.project = project;
        source.setEditable(false);
        source.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13));
        resultList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) open(resultList.getSelectedValue());
        });

        JPanel controls = new JPanel(new BorderLayout(8, 0));
        controls.add(query, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        addAction(buttons, "Search", this::search);
        addAction(buttons, "Callers", this::callers);
        addAction(buttons, "Impact", this::impact);
        controls.add(buttons, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout());
        top.add(controls, BorderLayout.CENTER);
        top.add(status, BorderLayout.SOUTH);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(resultList), new JScrollPane(source));
        split.setResizeWeight(0.35);
        frame.add(top, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.setPreferredSize(new Dimension(1100, 700));
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    private void addAction(JPanel buttons, String label, Function<String, QueryView> task) {
        var button = new JButton(label);
        button.addActionListener(event -> run(task));
        actions.add(button);
        buttons.add(button);
    }

    private void run(Function<String, QueryView> task) {
        String input = query.getText();
        if (input.isBlank()) {
            status.setText("Enter search text or Class.method");
            return;
        }
        actions.forEach(button -> button.setEnabled(false));
        status.setText("Working...");
        new QueryWorker(task, input).execute();
    }

    private final class QueryWorker extends SwingWorker<QueryView, Void> {
        private final Function<String, QueryView> task;
        private final String input;

        private QueryWorker(Function<String, QueryView> task, String input) {
            this.task = task;
            this.input = input;
        }

        @Override
        protected QueryView doInBackground() {
            return task.apply(input);
        }

        @Override
        protected void done() {
            actions.forEach(button -> button.setEnabled(true));
            try {
                QueryView view = get();
                results.clear();
                view.items().forEach(results::addElement);
                status.setText(view.summary());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                status.setText("Interrupted");
            } catch (ExecutionException exception) {
                status.setText("Query failed: " + exception.getCause().getMessage());
            }
        }
    }

    private QueryView search(String pattern) {
        var matches = engine.search(project, pattern).matches();
        var items = matches.stream()
                .map(match -> new NavigationItem(
                        match.file() + ":" + match.line() + "  " + match.context(),
                        Path.of(match.file()), match.line()))
                .toList();
        return new QueryView(matches.size() + " matches", items);
    }

    private QueryView callers(String method) {
        CallersResult result = engine.callers(project, method);
        if (result.status() == CallersResult.Status.AMBIGUOUS) {
            return new QueryView("Ambiguous method; use a qualified signature",
                    result.candidates().stream()
                            .map(candidate -> new NavigationItem(candidate, null, 0)).toList());
        }
        var items = result.callers().stream()
                .map(caller -> new NavigationItem(
                        caller.className() + "." + caller.method() + ":" + caller.line(),
                        project.fileForClass(caller.className()).orElse(null), caller.line()))
                .toList();
        return new QueryView(result.status() == CallersResult.Status.NOT_FOUND
                ? "Method not found" : items.size() + " callers", items);
    }

    private QueryView impact(String method) {
        var result = engine.impact(project, method, false);
        if (result instanceof JsrcEngine.ImpactResult.Missing missing) {
            return new QueryView("Method not found; text usages: " + missing.textUsages(),
                    List.of());
        }
        var found = (JsrcEngine.ImpactResult.Found) result;
        var items = found.affectedClasses().stream()
                .map(name -> new NavigationItem(name, project.fileForClass(name).orElse(null), 1))
                .toList();
        return new QueryView(found.riskLevel() + " risk; " + found.directCallers()
                + " direct callers; " + found.transitiveCallers() + " affected classes", items);
    }

    private void open(NavigationItem item) {
        if (item == null || item.file() == null) return;
        var snapshot = project.sourceText(item.file());
        if (snapshot.isEmpty()) {
            status.setText("Source not found in project snapshot: " + item.file());
            return;
        }
        try {
            source.setText(snapshot.orElseThrow());
            source.setCaretPosition(0);
            int line = Math.max(1, item.line());
            int offset = source.getLineStartOffset(
                    Math.min(line - 1, source.getLineCount() - 1));
            source.setCaretPosition(offset);
            source.requestFocusInWindow();
        } catch (javax.swing.text.BadLocationException exception) {
            status.setText("Cannot open " + item.file() + ": " + exception.getMessage());
        }
    }

    private record QueryView(String summary, List<NavigationItem> items) {}

    private record NavigationItem(String label, Path file, int line) {
        @Override public String toString() { return label; }
    }

    /** Launches the local prototype for a Maven, Gradle, or plain Java project. */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: IdePrototype <project-directory>");
            System.exit(2);
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("A graphical session is required.");
            System.exit(2);
        }
        IdeProject project = IdeProject.open(Path.of(args[0]));
        SwingUtilities.invokeLater(() -> new IdePrototype(project).frame.setVisible(true));
    }
}
