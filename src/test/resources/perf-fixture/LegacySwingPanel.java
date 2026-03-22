package com.example;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Vector;
import java.util.Hashtable;

public class LegacySwingPanel implements ActionListener {

    private static List<String> cache = new ArrayList<>();
    private ThreadLocal<String> userContext = new ThreadLocal<>();
    private Vector<String> items = new Vector<>();

    // EDT blocking — query in listener
    public void actionPerformed(ActionEvent e) {
        Connection conn = null;
        PreparedStatement ps = conn.prepareStatement("SELECT * FROM orders WHERE id = ?");
        ResultSet rs = ps.executeQuery();
    }

    // Cursor leak — no close, no try-with-resources
    public void loadData(Connection conn, String id) {
        PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = " + id);
        ResultSet rs = ps.executeQuery();
        // no close!
    }

    // Table fire in loop
    public void updateTable(javax.swing.table.DefaultTableModel model, List<String[]> rows) {
        for (String[] row : rows) {
            model.addRow(row);
            model.fireTableDataChanged();
        }
    }

    @Override
    protected void finalize() {
        // bad practice
    }
}
