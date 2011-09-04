// ========================================================================
// Copyright 2011 Sauce Labs, Inc
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
// ========================================================================

package com.saucelabs.sauceconnect

import java.awt.EventQueue
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.util.prefs.Preferences

import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JTextField
import javax.swing.JTextPane
import javax.swing.Timer

import com.jgoodies.forms.factories.FormFactory
import com.jgoodies.forms.layout.ColumnSpec
import com.jgoodies.forms.layout.FormLayout
import com.jgoodies.forms.layout.RowSpec

import javax.swing.JScrollPane
import javax.swing.JProgressBar

object SauceGUI {
  def main(args: Array[String]) {
    EventQueue.invokeLater(new Runnable() {
      def run() {
        try {
          val window = new SauceGUI()
          window.frmSauceConnect.setVisible(true)
        } catch {
          case e:Exception => e.printStackTrace()
        }
      }
    })
  }
}

class SauceGUI {
  val STATE_INIT = 0
  val STATE_CONNECTING = 1
  val STATE_CONNECTED = 2

  var frmSauceConnect: JFrame = null
  var username: JTextField = null
  var apikey: JTextField = null
  var logPane: JTextPane = null
  var logScroll: JScrollPane = null
  var progressBar: JProgressBar = null
  var startButton: JButton = null

  var state = STATE_INIT

  initialize()
  loadPreferences()

  def start() {
    frmSauceConnect.setVisible(true)
  }

  /**
   * Initialize the contents of the frame.
   */
  def initialize() {
    frmSauceConnect = new JFrame()
    frmSauceConnect.setTitle("Sauce Connect")
    frmSauceConnect.setBounds(100, 100, 770, 515)
    frmSauceConnect.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE)
    frmSauceConnect.getContentPane().setLayout(
      new FormLayout(Array[ColumnSpec](FormFactory.RELATED_GAP_COLSPEC,
                                       ColumnSpec.decode("default:grow"),
                                       FormFactory.RELATED_GAP_COLSPEC,
                                       ColumnSpec.decode("default:grow"),
                                       FormFactory.RELATED_GAP_COLSPEC),
                     Array[RowSpec](FormFactory.RELATED_GAP_ROWSPEC,
                                    FormFactory.DEFAULT_ROWSPEC,
                                    FormFactory.RELATED_GAP_ROWSPEC,
                                    FormFactory.DEFAULT_ROWSPEC,
                                    FormFactory.RELATED_GAP_ROWSPEC,
                                    FormFactory.DEFAULT_ROWSPEC,
                                    FormFactory.RELATED_GAP_ROWSPEC,
                                    FormFactory.DEFAULT_ROWSPEC,
                                    FormFactory.RELATED_GAP_ROWSPEC,
                                    RowSpec.decode("default:grow"))))

    val lblSauceOndemandUsername = new JLabel("Sauce OnDemand Username")
    frmSauceConnect.getContentPane().add(lblSauceOndemandUsername, "2, 2, right, default")

    username = new JTextField()
    frmSauceConnect.getContentPane().add(username, "4, 2, fill, default")
    username.setColumns(10)

    val lblSauceOndemandApi = new JLabel("Sauce OnDemand API Key")
    frmSauceConnect.getContentPane().add(lblSauceOndemandApi, "2, 4, right, default")

    apikey = new JTextField()
    frmSauceConnect.getContentPane().add(apikey, "4, 4, fill, default")
    apikey.setColumns(10)

    startButton = new JButton("Connect")
    startButton.addActionListener(new ActionListener() {
      def actionPerformed(arg0: ActionEvent) {
        connectPressed()
      }
    })
    frmSauceConnect.getContentPane().add(startButton, "2, 6, 3, 1")

    progressBar = new JProgressBar()
    progressBar.setMaximum(1000)
    frmSauceConnect.getContentPane().add(progressBar, "2, 8, 3, 1")

    logScroll = new JScrollPane()
    frmSauceConnect.getContentPane().add(logScroll, "2, 10, 3, 1, fill, fill")

    logPane = new JTextPane()
    logPane.setEditable(false)
    logScroll.setViewportView(logPane)
  }

  def launchProgressUpdater() {
    val start = System.currentTimeMillis()
    val clock = new Timer(30, new ActionListener() {
      def actionPerformed(e: ActionEvent) {
        val lines = logPane.getText.count(x => x == '\n')
        if (lines == 1) {
          // assume Jython takes ~5 seconds to start up
          val progress = (500.0-(1000000.0/((System.currentTimeMillis()-start)+2000)))
          progressBar.setValue(progress.toInt)
        } else {
          // 18 lines = running
          // if we're printing text, we're past 50% started
          progressBar.setValue(500 + (lines*500/18))
        }
        if (logPane.getText().indexOf("You may start your tests.") != -1) {
          progressBar.setValue(1000)
          e.getSource.asInstanceOf[Timer].stop()
          JOptionPane.showMessageDialog(frmSauceConnect,
                                        "Sauce Connect is ready. You may run your tests", "Connected",
                                        JOptionPane.INFORMATION_MESSAGE)
          setupDisconnect()
        }
      }
    })
    clock.start()
  }

  def savePreferences() {
    val prefs = Preferences.userNodeForPackage(getClass())
    prefs.put("username", username.getText())
    prefs.put("api_key", apikey.getText())
  }

  def loadPreferences() {
    val prefs = Preferences.userNodeForPackage(getClass())
    username.setText(prefs.get("username", ""))
    apikey.setText(prefs.get("api_key", ""))
  }

  def connectPressed() {
    state match {
      case STATE_INIT => {
        savePreferences()
        launchProgressUpdater()
        synchronized {
          state = STATE_CONNECTING
          logPane.setText("Connecting...\n")
          startButton.setEnabled(false)
          notifyAll()
        }
      }

      case STATE_CONNECTED => {
        disconnect()
      }
    }
  }

  def setupDisconnect() {
    state = STATE_CONNECTED
    startButton.setText("Disconnect")
    startButton.setEnabled(true)
  }

  def disconnect() {
    System.exit(0)
  }
}
