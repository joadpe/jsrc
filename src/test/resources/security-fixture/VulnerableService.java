package com.example;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;

public class VulnerableService {

    private static final String password = "admin123";
    private static final String apiKey = "sk-abc123def456";

    // SQL injection — string concatenation
    public void findUser(Connection conn, String name) throws Exception {
        PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE name = '" + name + "'");
        ps.executeQuery();
    }

    // Path traversal — user input in file path
    public File getFile(String userInput) {
        return new File("/data/" + userInput);
    }

    // XXE — DocumentBuilderFactory without secure config
    public void parseXml(String xml) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes()));
    }

    // Insecure deserialization
    public Object deserialize(byte[] data) throws Exception {
        java.io.ObjectInputStream ois = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(data));
        return ois.readObject();
    }

    // Weak crypto
    public byte[] hashPassword(String pwd) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        return md.digest(pwd.getBytes());
    }

    // Insecure random
    public int generateToken() {
        return new java.util.Random().nextInt();
    }
}
