package test;

import java.applet.Applet;
import java.awt.Graphics;

/**
 * Uses java.applet.Applet — removed in Java 17.
 */
public class AppletLegacy extends Applet {
    @Override
    public void init() {
        // legacy applet init
    }

    @Override
    public void paint(Graphics g) {
        g.drawString("Hello", 50, 25);
    }
}
