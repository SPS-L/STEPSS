/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package my.ramses;

import java.io.IOException;
import java.io.OutputStream;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 *
 * @author p3tris
 */
class TextareaOutputStream extends OutputStream {

    private final JTextArea area;
    private final StringBuffer buf = new StringBuffer(128);


    public TextareaOutputStream(final JTextArea area) throws IOException {
        this.area = area;
    }

    @Override
    public void write(int c) throws IOException {
        // append character to buffer
        buf.append((char) c);
        // and newline appends to textarea
        if (c == '\n') {
            flush();
        }
    }

    @Override
    public void close() {
        flush();
    }

    @Override
    public void flush() {
        SwingUtilities.invokeLater(new Runnable() {

            String str = buf.toString();

            @Override
            public void run() {
                area.append(str);
            }
        });
        buf.setLength(0);
    }

    public void message(String msg) {
        if (buf.charAt(buf.length() - 1) != '\n') {
            buf.append('\n');
        }
        buf.append(msg);
        buf.append('\n');
        flush();
    }
}