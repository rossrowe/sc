package com.saucelabs.sauceconnect;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.JTextPane;

import com.jgoodies.forms.factories.FormFactory;
import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.RowSpec;
import javax.swing.JScrollPane;

public class SauceGUI {

    private JFrame frmSauceConnect;
    public JTextField username;
    public JTextField apikey;
    public JTextPane logPane;
    public JScrollPane logScroll;

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
        
        final JButton startButton = new JButton("Connect");
        startButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent arg0) {
                synchronized(SauceGUI.this){
                    logPane.setText("Connecting...");
                    startButton.setEnabled(false);
                    SauceGUI.this.notifyAll();
                }
            }
        });
        frmSauceConnect.getContentPane().add(startButton, "2, 6, 3, 1");
        
        logScroll = new JScrollPane();
        frmSauceConnect.getContentPane().add(logScroll, "2, 8, 3, 1, fill, fill");
        
        logPane = new JTextPane();
        logPane.setEditable(false);
        logScroll.setViewportView(logPane);
    }
}
