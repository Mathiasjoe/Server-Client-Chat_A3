package no.ntnu.datakomm.chat;

import java.io.*;
import java.lang.reflect.Array;
import java.net.*;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class TCPClient {
    private PrintWriter toServer;
    private BufferedReader fromServer;
    private Socket connection;

    private static final String CMD_PUBLIC_MESSAGE = "msg";
    private static final String CMD_PRIVATE_MESSAGE = "privmsg";
    private static final String CMD_MSG_OK = "msgok";
    private static final String CMD_MSG_ERROR = "msgerr";
    private static final String CMD_ERROR = "cmderr";
    private static final String CMD_HELP = "help";
    private static final String CMD_LOGIN = "login";
    private static final String CMD_LOGIN_OK = "loginok";
    private static final String CMD_LOGIN_ERROR = "loginerr";
    private static final String CMD_USERS = "users";
    private static final String CMD_SUPPORTED = "supported";

    private String lastError = null; // Store message for the last error

    private final List<ChatListener> listeners = new LinkedList<>();

    /**
     * Connect to a chat server.
     *
     * @param host host name or IP address of the chat server
     * @param port TCP port of the chat server
     * @return True on success, false otherwise
     */
    public boolean connect(String host, int port) {
        boolean success = false;
        try {
            if (!isConnectionActive()) {
                connection = new Socket(host, port);
                success = connection.isConnected();
            } else {
                lastError = ("Already connected!");
            }
        } catch (IOException e) {
            lastError = ("Could not connect to the server.");
        }
        return success;
    }

    /**
     * Close the socket. This method must be synchronized, because several
     * threads may try to call it. For example: When "Disconnect" button is
     * pressed in the GUI thread, the connection will get closed. Meanwhile, the
     * background thread trying to read server's response will get error in the
     * input stream and may try to call this method when the socket is already
     * in the process of being closed. with "synchronized" keyword we make sure
     * that no two threads call this method in parallel.
     */
    public synchronized void disconnect() {
        try {
            if(isConnectionActive())
            connection.close();
            onDisconnect();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @return true if the connection is active (opened), false if not.
     */
    public boolean isConnectionActive() {
        return connection != null;
    }

    /**
     * Send a command to server.
     *
     * @param cmd A command. It should include the command word and optional attributes, according to the protocol.
     * @return true on success, false otherwise
     */
    private boolean sendCommand(String cmd) {
        try {
            toServer = new PrintWriter(connection.getOutputStream(), true);
            toServer.println(cmd);
            return true;

        } catch (IOException e) {
            lastError = ("Could not send message: + " + e.getMessage());
            return false;
        }

    }

    /**
     * Send a public message to all the recipients.
     *
     * @param message Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPublicMessage(String message) {
        boolean success = false;
        try{
            sendCommand(CMD_PUBLIC_MESSAGE + " " + message );
            success = true;
        }catch (Exception e){
            lastError = ("Failed to send public message: " + e.getMessage());
        }
        return success;
    }

    /**
     * Send a login request to the chat server.
     *
     * @param username Username to use
     */
    public void tryLogin(String username) {
        try{
            sendCommand(CMD_LOGIN + " " + username);
        }catch (Exception e){
            lastError = ("Login Error: " + e.getMessage());
        }
    }

    /**
     * Send a request for latest user list to the server. To get the new users,
     * clear your current user list and use events in the listener.
     */
    public void refreshUserList() {
        this.sendCommand(CMD_USERS);
    }

    /**
     * Send a private message to a single recipient.
     *
     * @param recipient username of the chat user who should receive the message
     * @param message   Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPrivateMessage(String recipient, String message) {
        boolean success = false;
        try {
            sendCommand(CMD_PRIVATE_MESSAGE + " " + recipient + " " + message);
            success = true;
        } catch (Exception e) {
            lastError = ("Error sending priv message" + e.getMessage());
        }
        return success;
    }


    /**
     * Send a request for the list of commands that server supports.
     */
    public void askSupportedCommands() {
        sendCommand(CMD_HELP);
    }


    /**
     * Wait for chat server's response
     *
     * @return one line of text (one command) received from the server
     */
    private String waitServerResponse() {
        String msgFromServer = "";
        if(isConnectionActive()) {
            try {
                fromServer = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String messageFromServer = fromServer.readLine();
                if(messageFromServer != null) {
                    msgFromServer = messageFromServer;
                }
            } catch (IOException e) {
                lastError = ("Reading from socket failed: " + e.getMessage());
                msgFromServer = lastError;
                disconnect();
            }
        }
        return msgFromServer;
    }

    /**
     * Get the last error message
     *
     * @return Error message or "" if there has been no error
     */
    public String getLastError() {
        if(lastError != null) {
            return lastError;
        } else {
            return "";
        }
    }

    /**
     * Start listening for incoming commands from the server in a new CPU thread.
     */
    public void startListenThread() {
        // Call parseIncomingCommands() in the new thread.
        Thread t = new Thread(() -> {
            parseIncomingCommands();
        });
        t.start();
    }

    /**
     * Read incoming messages one by one, generate events for the listeners. A loop that runs until
     * the connection is closed.
     */
    private void parseIncomingCommands() {
        while (isConnectionActive()) {
            try {
                String[] serverResponseParts = waitServerResponse().split("\\s+", 2);
                /// gets the first word from the response
                String serverFirstResponsePart = serverResponseParts[0];

                switch (serverFirstResponsePart) {
                    case CMD_LOGIN_OK -> onLoginResult(true, null);
                    case CMD_LOGIN_ERROR -> onLoginResult(false, "Login error");
                    case CMD_PUBLIC_MESSAGE -> {
                        String[] serverResponse = serverResponseParts[1].split("\\s+", 2);
                        onMsgReceived(false, serverResponse[0], serverResponse[1]);
                    }
                    case CMD_MSG_OK -> System.out.println("Message ok");
                    case CMD_MSG_ERROR -> onMsgError(serverResponseParts[1]);
                    case CMD_PRIVATE_MESSAGE -> {
                        String[] privMsgResponse = serverResponseParts[1].split("\\s+", 2);
                        onMsgReceived(true, privMsgResponse[0], privMsgResponse[1]);
                    }
                    case CMD_USERS -> {
                        System.out.println(serverResponseParts[1]);
                        String[] userList = serverResponseParts[1].split("\\s+");
                        onUsersList(userList);
                        break;
                    }
                    case CMD_SUPPORTED -> {
                        String[] supported = serverResponseParts[1].split("\\s+");
                        onSupported(supported);
                    }
                    case CMD_ERROR -> onCmdError(serverResponseParts[0]);
                    default -> System.out.println("Nothing");
                }
            } catch (NullPointerException e) {
                System.out.println("Error from server" + e.getMessage());
            }
        }
    }

    /**
     * Register a new listener for events (login result, incoming message, etc)
     *
     * @param listener
     */
    public void addListener(ChatListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Unregister an event listener
     *
     * @param listener
     */
    public void removeListener(ChatListener listener) {
        listeners.remove(listener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////////////
    // The following methods are all event-notificators - notify all the listeners about a specific event.
    // By "event" here we mean "information received from the chat server".
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Notify listeners that login operation is complete (either with success or
     * failure)
     *
     * @param success When true, login successful. When false, it failed
     * @param errMsg  Error message if any
     */
    private void onLoginResult(boolean success, String errMsg) {
        for (ChatListener l : listeners) {
            l.onLoginResult(success, errMsg);
        }
    }

    /**
     * Notify listeners that socket was closed by the remote end (server or
     * Internet error)
     */
    private void onDisconnect() {
        for (ChatListener l : listeners) {
            l.onDisconnect();
        }

    }

    /**
     * Notify listeners that server sent us a list of currently connected users
     *
     * @param users List with usernames
     */
    private void onUsersList(String[] users) {
        for(ChatListener l : listeners){
            l.onUserList(users);
        }
    }

    /**
     * Notify listeners that a message is received from the server
     *
     * @param priv   When true, this is a private message
     * @param sender Username of the sender
     * @param text   Message text
     */
    private void onMsgReceived(boolean priv, String sender, String text) {
        TextMessage textMessage = new TextMessage(sender, priv, text);
        for(ChatListener l : listeners){
                l.onMessageReceived(textMessage);
        }
    }

    /**
     * Notify listeners that our message was not delivered
     *
     * @param errMsg Error description returned by the server
     */
    private void onMsgError(String errMsg) {
        for (ChatListener l : listeners) {
            l.onMessageError(errMsg);
        }
    }

    /**
     * Notify listeners that command was not understood by the server.
     *
     * @param errMsg Error message
     */
    private void onCmdError(String errMsg) {
        for (ChatListener l : listeners) {
            l.onCommandError(errMsg);
        }
    }

    /**
     * Notify listeners that a help response (supported commands) was received
     * from the server
     *
     * @param commands Commands supported by the server
     */
    private void onSupported(String[] commands) {
        for (ChatListener l : listeners) {
            l.onSupportedCommands(commands);
        }
    }
}

