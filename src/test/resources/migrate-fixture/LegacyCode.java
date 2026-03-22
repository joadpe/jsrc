package com.example;

import java.util.Date;
import java.util.Calendar;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Vector;
import java.util.Hashtable;
import javax.servlet.http.HttpServletRequest;
import javax.persistence.Entity;
import javax.ejb.Stateless;

public class LegacyCode {

    // Diamond operator candidate
    private HashMap<String, List<Integer>> map = new HashMap<String, List<Integer>>();

    // Vector usage
    private Vector<String> items = new Vector<String>();

    // Hashtable usage
    private Hashtable<String, Object> cache = new Hashtable<String, Object>();

    // StringBuffer usage
    private StringBuffer buffer = new StringBuffer();

    // Legacy date
    public void processDate() {
        Date now = new Date();
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    }

    // Try/finally/close candidate
    public void readFile() throws Exception {
        java.io.InputStream is = new java.io.FileInputStream("test.txt");
        try {
            is.read();
        } finally {
            is.close();
        }
    }

    // instanceof without pattern matching
    public void process(Object obj) {
        if (obj instanceof String) {
            String s = (String) obj;
            System.out.println(s);
        }
    }

    // finalize
    @Override
    protected void finalize() {
        // cleanup
    }
}
