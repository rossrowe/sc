package com.saucelabs.sauceconnect;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.prefs.Preferences;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.Timer;

import com.jgoodies.forms.factories.FormFactory;
import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.RowSpec;

import javax.swing.JScrollPane;
import javax.swing.JProgressBar;

public class SauceGUI {

    private JFrame frmSauceConnect;
    public JTextField username;
    public JTextField apikey;
    public JTextPane logPane;
    public JScrollPane logScroll;
    private JProgressBar progressBar;
    public JButton startButton;
    private int state = STATE_INIT;
    
    private static final int STATE_INIT = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    /**
     * Launch the application.
     */
    public static void main(String[] args) {
        EventQueue.invokeLater(new Runnable() {
            public void run() {
                try {
                    SauceGUI window = new SauceGUI();
                    window.frmSauceConnect.setVisible(true);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
    
    public void start(){
        this.frmSauceConnect.setVisible(true);
    }

    /**
     * Create the application.
     */
    public SauceGUI() {
        initialize();
        loadPreferences();
    }

    /**
     * Initialize the contents of the frame.
     */
    private void initialize() {
        frmSauceConnect = new JFrame();
        frmSauceConnect.setTitle("Sauce Connect");
        frmSauceConnect.setBounds(100, 100, 770, 515);
        frmSauceConnect.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frmSauceConnect.getContentPane().setLayout(new FormLayout(new ColumnSpec[] {
                FormFactory.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("default:grow"),
                FormFactory.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("default:grow"),
                FormFactory.RELATED_GAP_COLSPEC,},
            new RowSpec[] {
                FormFactory.RELATED_GAP_ROWSPEC,
                FormFactory.DEFAULT_ROWSPEC,
                FormFactory.RELATED_GAP_ROWSPEC,
                FormFactory.DEFAULT_ROWSPEC,
                FormFactory.RELATED_GAP_ROWSPEC,
                FormFactory.DEFAULT_ROWSPEC,
                FormFactory.RELATED_GAP_ROWSPEC,
                FormFactory.DEFAULT_ROWSPEC,
                FormFactory.RELATED_GAP_ROWSPEC,
                RowSpec.decode("default:grow"),}));
        
        JLabel lblSauceOndemandUsername = new JLabel("Sauce OnDemand Username");
        frmSauceConnect.getContentPane().add(lblSauceOndemandUsername, "2, 2, right, default");
        
        username = new JTextField();
        frmSauceConnect.getContentPane().add(username, "4, 2, fill, default");
        username.setColumns(10);
        
        JLabel lblSauceOndemandApi = new JLabel("Sauce OnDemand API Key");
        frmSauceConnect.getContentPane().add(lblSauceOndemandApi, "2, 4, right, default");
        
        apikey = new JTextField();
        frmSauceConnect.getContentPane().add(apikey, "4, 4, fill, default");
        apikey.setColumns(10);
        
        startButton = new JButton("Connect");
        startButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent arg0) {
                SauceGUI.this.connectPressed();
            }
        });
        frmSauceConnect.getContentPane().add(startButton, "2, 6, 3, 1");
        
        progressBar = new JProgressBar();
        progressBar.setMaximum(1000);
        frmSauceConnect.getContentPane().add(progressBar, "2, 8, 3, 1");
        
        logScroll = new JScrollPane();
        frmSauceConnect.getContentPane().add(logScroll, "2, 10, 3, 1, fill, fill");
        
        logPane = new JTextPane();
        logPane.setEditable(false);
        logScroll.setViewportView(logPane);
    }

    protected void launchProgressUpdater() {
        final long start = System.currentTimeMillis();
        final Timer clock = new Timer(30, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int lines = LineCounter.countLines(SauceGUI.this.logPane.getText());
                if(lines == 1){
                    // assume Jython takes ~5 seconds to start up
                    double progress = (500.0-(1000000.0/((System.currentTimeMillis()-start)+2000)));
                    SauceGUI.this.progressBar.setValue((int)progress);
                } else {
                    // 18 lines = running
                    // if we're printing text, we're past 50% started
                    SauceGUI.this.progressBar.setValue(500 + (lines*500/18));
                }
                if(SauceGUI.this.logPane.getText().indexOf("You may start your tests.") != -1){
                    SauceGUI.this.progressBar.setValue(1000);
                    ((Timer)e.getSource()).stop();
                    JOptionPane.showMessageDialog(SauceGUI.this.frmSauceConnect,
                            "Sauce Connect is ready. You may run your tests", "Connected",
                            JOptionPane.INFORMATION_MESSAGE);
                    SauceGUI.this.setupDisconnect();
                }
            }
        });
        clock.start();
    }

    protected void savePreferences() {
        Preferences prefs = Preferences.userNodeForPackage(getClass());
        prefs.put("username", this.username.getText());
        prefs.put("api_key", this.apikey.getText());
    }
    
    protected void loadPreferences() {
        Preferences prefs = Preferences.userNodeForPackage(getClass());
        this.username.setText(prefs.get("username", ""));
        this.apikey.setText(prefs.get("api_key", ""));
    }
    
    protected void connectPressed() {
        switch(this.state) {
        case STATE_INIT:
            savePreferences();
            launchProgressUpdater();
            synchronized(this) {
                this.state = STATE_CONNECTING;
                logPane.setText("Connecting...\n");
                startButton.setEnabled(false);
                SauceGUI.this.notifyAll();
            }
            break;
        
        case STATE_CONNECTED:
            disconnect();
            break;
        }
    }
    
    protected void setupDisconnect() {
        this.state = STATE_CONNECTED;
        startButton.setText("Disconnect");
        startButton.setEnabled(true);
    }
    
    protected void disconnect() {
        System.exit(0);
    }
}
