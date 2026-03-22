package com.example;

import java.sql.Connection;
import java.sql.PreparedStatement;

public class SecureService {

    // Parameterized query — safe
    public void findUser(Connection conn, String name) throws Exception {
        PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE name = ?");
        ps.setString(1, name);
        ps.executeQuery();
        ps.close();
    }

    // No hardcoded secrets — safe
    public String getConfig(String key) {
        return System.getProperty(key);
    }
}
