package com.example;

import java.util.List;
import java.text.SimpleDateFormat;
import java.util.regex.Pattern;

public class JavaAntiPatterns {

    private final List<String> items;

    public JavaAntiPatterns(List<String> items) {
        this.items = items;
    }

    // String concatenation in loop — O(N²)
    public String joinAll() {
        String result = "";
        for (String item : items) {
            result += item + ", ";
        }
        return result;
    }

    // String.format in loop
    public void logAll() {
        for (String item : items) {
            String msg = String.format("Processing item: %s at %d", item, System.currentTimeMillis());
            System.out.println(msg);
        }
    }

    // SimpleDateFormat in loop
    public void formatDates(List<java.util.Date> dates) {
        for (java.util.Date d : dates) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            System.out.println(sdf.format(d));
        }
    }

    // Pattern.compile in loop
    public int countMatches(List<String> lines, String regex) {
        int count = 0;
        for (String line : lines) {
            Pattern p = Pattern.compile(regex);
            if (p.matcher(line).matches()) count++;
        }
        return count;
    }

    // Reflection in loop
    public void processReflective(List<Object> objects) throws Exception {
        for (Object obj : objects) {
            java.lang.reflect.Method m = obj.getClass().getMethod("toString");
            m.invoke(obj);
        }
    }

    // Connection in loop
    public void fetchAll(List<String> urls) throws Exception {
        for (String url : urls) {
            java.net.URL u = new java.net.URL(url);
            u.openConnection();
        }
    }

    // Stream created in loop
    public void filterEach(List<List<String>> batches, String prefix) {
        for (List<String> batch : batches) {
            long count = batch.stream().filter(s -> s.startsWith(prefix)).count();
            System.out.println(count);
        }
    }

    // N+1 query problem
    public void loadDetails(List<Long> ids, Object repository) throws Exception {
        for (Long id : ids) {
            java.sql.Connection conn = null;
            java.sql.PreparedStatement ps = conn.prepareStatement("SELECT * FROM orders WHERE id = ?");
            ps.executeQuery();
        }
    }

    // List.remove in loop — O(N²)
    public void removeOdds(List<Integer> numbers) {
        for (int i = 0; i < numbers.size(); i++) {
            if (numbers.get(i) % 2 != 0) {
                numbers.remove(i);
                i--;
            }
        }
    }
}
