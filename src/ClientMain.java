import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RemoteObject;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;

public class ClientMain extends RemoteObject implements NotifyFollowersInterface{
    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_CYAN = "\u001B[36m";
    public static final String ANSI_BLUE = "\u001B[34m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_RED = "\u001B[31m";

    public static final String CONFIG_FILE = "./../config/CONFIG_Client.txt";  //Name of CONFIG file for client

    private static String SERVERADDRESS;  //Server address
    private static int TCPPORT;   //TCP port
    private static String MULTICASTADDRESS;   //Multicast address
    private static int MCASTPORT; //Multicast port
    private static String REGHOST;   //RMI registry host
    private static int REGPORT;   //RMI registry port
    private static int REGPORTCB; //RMI CallBack registry port
    private static RegisterService stub;
    private static MulticastSocket mcastSocket;
    private static String username; //Username associated to this client connection
    private static boolean logged;
    private static List<User> followers; //lista interna degli utenti followers dell'utente username

    //LineReader substitutes BufferedReader in a case of input (commands from input)
    //and helps user navigate past commands history
    private static LineReader reader;
    //Lock used only to handle main System.out.println()
    private static final ReentrantLock lock = new ReentrantLock();

    public ClientMain(){
        super();
    }

    public static void main(String[] args) throws IOException{

        if(args.length != 0){
            System.out.println("Usage: java -cp \".:../jline-reader-3.21.0.jar:.:../jline-terminal-3.21.0.jar\" ClientMain");
            System.exit(-1);
        }
        else{
            boolean correctRead;
            correctRead = readConfigFile(CONFIG_FILE);
            if(!correctRead)
                System.exit(-1);
        }
        ServerNotifyInterface serverNotify = null;
        NotifyFollowersInterface stubNotify = null;
        try {
            //Getting the registry (on port REGPORT)
            Registry registry = LocateRegistry.getRegistry(REGPORT);
            // Looking up the registry for the remote object
            stub = (RegisterService) registry.lookup("RegisterService");
            //CALLBACK RMI
            //Getting the registry (on port REGPORTCB)
            Registry registryCallback = LocateRegistry.getRegistry(REGPORTCB);
            serverNotify = (ServerNotifyInterface) registryCallback.lookup("ServerNotify");
            //register for the callback
            NotifyFollowersInterface callbackObj = new ClientMain();
            stubNotify = (NotifyFollowersInterface) UnicastRemoteObject.exportObject(callbackObj, 0);
        }catch(Exception e){
            System.err.println("Client exception during rmi registry: " + e.toString());
            System.exit(-1);
        }

        followers = new CopyOnWriteArrayList<User>();
        //Reader to read line from stdin
        reader = LineReaderBuilder.builder().build();
        //TCP connection building
        InetAddress serverAddr = InetAddress.getByName(SERVERADDRESS);
        //System.out.println("addr =" +serverAddr);  //DEBUG
        Thread mcastThread = null; //Thread to handle multicast

        boolean msgSent;
        try(Socket serverSocket = new Socket(SERVERADDRESS, TCPPORT))
        {
            try(BufferedReader input = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()));
                PrintWriter output = new PrintWriter(serverSocket.getOutputStream(), true))
            {
                //logged must be set to TRUE otherwise command isn't recognized
                logged = false;
                username = null;
                String lineReceived = new String("");
                while(true){
                    msgSent = false;
                    System.out.println(ANSI_CYAN+"Insert a command:"+ANSI_RESET);
                    String commandRead = reader.readLine();

                    String[] arrCommandRead = commandRead.split(" ");
                    //quit from client and interrupt connection
                    if(arrCommandRead[0].equals(":q!")){
                        if(logged){
                            serverNotify.unregisterForCallback((NotifyFollowersInterface) stubNotify);
                        }
                        break;
                    }
                    //case of registration with RMI
                    if((arrCommandRead[0].toLowerCase()).equals("register")){
                        if(!logged)
                            registerRMI(arrCommandRead);
                        else
                            System.out.println(ANSI_BLUE+"Needed logout command before register new user to Winsome"+ANSI_RESET);
                    }
                    else{
                        //System.out.println("I'm in else branch");  //DEBUG
                        if(!msgSent && !logged && !(arrCommandRead[0].toLowerCase()).equals("login")){
                            System.out.println(ANSI_BLUE+"Operation not permitted: no user logged!"+ANSI_RESET);
                            System.out.println(ANSI_BLUE+"if requiring user is registered "+
                                    "'login <username> <password>' must be first operation!"+ANSI_RESET);
                            msgSent = true;
                        }
                        if(!msgSent && logged && ((arrCommandRead[0].toLowerCase()).equals("login"))){
                            System.out.println(ANSI_BLUE+"There's a user logged yet, before must be logged out"+ANSI_RESET);
                            msgSent = true;
                        }
                        //List Followers command case //listFollowers
                        if(!msgSent && isListFollowersCommand(arrCommandRead)){
                            listFollowers();
                            msgSent = true;
                        }
                        if(!msgSent && ((arrCommandRead[0].toLowerCase()).equals("logout"))) {
                            commandRead = (commandRead.strip()).concat(" " + username);
                        }
                        if(!msgSent){
                            output.println(commandRead);

                            lineReceived = new String("");
                            String str = input.readLine();
                            if(str == null){
                                System.out.println(ANSI_RED+"Lost connection with server..."+ANSI_RESET);
                                System.out.println(ANSI_RED+"Client process Killed!"+ANSI_RESET);
                                input.close();
                                output.close();
                                serverSocket.close();
                                System.exit(-1);
                            }
                            while(str != null){
                                lineReceived = lineReceived.concat(str);
                                if(input.ready()){
                                    str = "\n"+input.readLine();
                                }else{
                                    str = null;
                                }
                            }
                            //if user logged out new user can login someone else
                            String[] arrReceived = lineReceived.split(" ");
                            //case logged in successfully
                            if ((arrReceived.length == 3) && arrReceived[1].equals("logged") && arrReceived[2].equals("in")) {
                                username = arrReceived[0];
                                serverNotify.registerForCallback((NotifyFollowersInterface) stubNotify); //do it after login
                                followers = serverNotify.restoreFollowers((NotifyFollowersInterface) stubNotify, username);
                                if(followers == null){
                                    followers = new CopyOnWriteArrayList<User>();
                                }
                                mcastSocket = new MulticastSocket(MCASTPORT);
                                mcastThread = new Thread(new MulticastTask(mcastSocket, MULTICASTADDRESS));
                                mcastThread.start();
                                logged = true;
                            }
                            //case logged out successfully
                            if ((arrReceived.length == 3) && arrReceived[1].equals("logged") && arrReceived[2].equals("out")) {
                                if (arrReceived[0].equals(username)) {
                                    serverNotify.unregisterForCallback((NotifyFollowersInterface) stubNotify);
                                    mcastThread.interrupt();
                                    mcastSocket.close();
                                    username = null;
                                    followers = null;
                                    logged = false;
                                }
                            }
                            lock.lock();
                            //Response received from Server
                            System.out.println(ANSI_BLUE+lineReceived+ANSI_RESET);
                            lock.unlock();
                        }

                    }
                }
            }catch(IOException e) {
                serverNotify.unregisterForCallback((NotifyFollowersInterface) stubNotify);
                System.out.println("In client " + serverSocket.getInetAddress().toString());
                System.out.println("ERROR in read() in run method: " + e);
            }catch(UserInterruptException uie){
                serverNotify.unregisterForCallback((NotifyFollowersInterface) stubNotify);
                System.out.println("In client " + serverSocket.getInetAddress().toString());
                System.out.println("ERROR in read() in run method: " + uie);
            }
        }catch (UnknownHostException e){
            System.out.println("Host Server not found: "+e);
        }catch(IOException ioe){
            System.out.println("ERROR during Socket building: "+ioe);
        }
        System.exit(0);
    }

    public static void registerRMI(String[] regCommand){
        //RMI Remote Building
        try{
            String newUsername = null;
            String password = null;
            //Parameters are checked in RMI register method
            if(regCommand.length > 1)
                newUsername = regCommand[1];
            if(regCommand.length > 2)
                password = regCommand[2];
            List<String> tags = new ArrayList<String>();
            for(int i=3; i<regCommand.length; i++){
                tags.add(regCommand[i].toLowerCase());
            }
            System.out.println(ANSI_BLUE+stub.register(newUsername, password, tags)+ANSI_RESET);
        }catch(Exception e){
            System.err.println("Client exception: " + e.toString());
            e.printStackTrace();
        }
    }

    //CALLBACKS METHODS
    @Override
    public void notifyFollowersUpdate(String userFollowed, List<User> followers, User newFollower) throws RemoteException{
        if(!username.equals(userFollowed)){
            return; //no write newFollower for this client (isn't the followed)
        }
        this.followers = new CopyOnWriteArrayList<User>(followers);
        String buf = null;
        if(reader.isReading()) {
            buf = reader.getBuffer().toString();
        }
        lock.lock();
        System.out.println("\n"+ANSI_GREEN+newFollower.getUsername()+" started to follow you!"+ANSI_RESET);
        System.out.println(ANSI_CYAN+"Insert a command:"+ANSI_RESET);
        System.out.print(buf);
        lock.unlock();
    }

    @Override
    public void notifyFollowersRemove(String userFollowed, List<User> followers, User oldFollower) throws RemoteException {
        if(!username.equals(userFollowed)){
            return; //no remove oldFollower for this client (isn't the followed)
        }
        this.followers = new CopyOnWriteArrayList<User>(followers);
        String buf = null;
        if(reader.isReading()) {
            buf = reader.getBuffer().toString();
        }
        lock.lock();
        System.out.println("\n"+ANSI_GREEN+oldFollower.getUsername()+" has stopped following you..."+ANSI_RESET);
        System.out.println(ANSI_CYAN+"Insert a command:"+ANSI_RESET);
        System.out.print(buf);
        lock.unlock();
    }

    /**
     * Open file CONFIG_Server.txt and read it line by line
     * @param configFile
     * @return true if file is correctly read
     * @throws IOException
     */
    public static boolean readConfigFile(String configFile) throws IOException {
        boolean bool = true;
        try(FileReader fileIn = new FileReader(configFile);
            BufferedReader bufLine = new BufferedReader(fileIn);)
        {
            String strRead = null;
            //Iterate until we're not at the End Of File
            while((strRead = bufLine.readLine()) != null){
                //take into account only useful line of CONFIG file
                if(strRead.length()!=0 && !strRead.startsWith("#")){
                    parseLineAndSetField(strRead); //method to set instances variables
                }
            }
        }catch(FileNotFoundException e){
            System.err.println("Error opening CONFIG_Server.txt");
            bool = false;
        }
        return bool;
    }

    /**
     * Split the string str in two part and set the field denoted by first part
     * with the value assigned to it into the file CONFIGServer.txt
     * @param str
     */
    public static void parseLineAndSetField(String str) {
        //Split the str string in two element:
        // NAME=strArr[0] and VALUE=strArr[1]
        String strArr[];
        strArr = str.split("=");
        switch (strArr[0]) {
            case "SERVER":
                SERVERADDRESS = strArr[1];
                break;
            case "TCPPORT":
                TCPPORT = Integer.parseInt(strArr[1]);
                break;
            case "MULTICAST":
                MULTICASTADDRESS = strArr[1];
                break;
            case "MCASTPORT":
                MCASTPORT = Integer.parseInt(strArr[1]);
                break;
            case "REGHOST":
                REGHOST = strArr[1];
                break;
            case "REGPORT":
                REGPORT = Integer.parseInt(strArr[1]);
                break;
            case "REGPORTCB":
                REGPORTCB = Integer.parseInt(strArr[1]);
                break;
            default:
                System.out.println("Unknown NAME in file CONFIG_Server.txt");
                System.out.println("Error setting Server parameters... ERROR FAILURE");
                System.exit(-1);
        }
    }


    //return true if the command inserted is "list followers"
    public static boolean isListFollowersCommand(String[] commandArr){
        boolean isThatCommand = false;
        if(commandArr.length == 2){
            if(commandArr[0].toLowerCase().equals("list") &&
                    commandArr[1].toLowerCase().equals("followers")){
                isThatCommand = true;
            }
        }
        return isThatCommand;
    }

    //return the intern list of the followers for the logged user
    public static void listFollowers(){
        String newLine = System.lineSeparator(); //newline character
        String strToReturn = new String("User \t\t|\tTag"+newLine);
        strToReturn = strToReturn.concat("-------------------------------"+newLine);
        String strToAppend;
        User tmpUser = null;
        List<String> tmpTags = null;
        Iterator<User> iterUsers = followers.iterator();
        while(iterUsers.hasNext()){
            tmpUser = iterUsers.next();
            tmpTags = tmpUser.getTags();
            //perform the string with the new line to show
            strToAppend = "";
            strToAppend = strToAppend.concat(tmpUser.getUsername()+"\t\t|\t");
            for(int i=0; i<tmpTags.size()-1; i++){
                strToAppend = strToAppend.concat(tmpTags.get(i)+", ");
            }
            strToAppend = strToAppend.concat(tmpTags.get(tmpTags.size()-1)+newLine);
            strToReturn = strToReturn.concat(strToAppend);
        }
        lock.lock();
        System.out.println(ANSI_BLUE+strToReturn+ANSI_RESET);
        lock.unlock();
    }

    //Perform the operation required to join and leave the multicast group
    //for receiving the message of 'reward calculated' from the server
    public static class MulticastTask extends Thread{
        private MulticastSocket multicastSocket = null;
        private InetAddress mcastGroupAddress = null;
        private InetSocketAddress mcastGroupSocketAddress = null;
        private NetworkInterface netIf = null;

        public MulticastTask(MulticastSocket socket, String address){
            try {
                this.multicastSocket = socket;
                socket.setReuseAddress(true);
                mcastGroupAddress = InetAddress.getByName(address);
                mcastGroupSocketAddress = new InetSocketAddress(mcastGroupAddress, MCASTPORT);
            }catch(IOException e){
                System.out.println("Error in MulticastTask (fatal_error) : "+e);
                System.exit(-1);
            }
        }

        @Override
        public void run(){
            try{
                netIf = NetworkInterface.getByName("bge0");
            }catch(SocketException se){
                System.out.println("Error occured in netIf allocation : "+se);
            }
            try {
                multicastSocket.joinGroup(new InetSocketAddress(mcastGroupAddress, 0), netIf);
            }catch(IOException e) {
                System.out.println("Error occurred in joinGroup() in MulticastTask: "+e);
            }
            while(!Thread.currentThread().isInterrupted()) //an interrupt is to be sent from the main thread before terminating
            {
                byte[] bytes = new byte[2048];
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                try {
                    multicastSocket.receive(packet);
                }catch(IOException e) {
                    if(multicastSocket.isClosed()){
                        return;
                    }
                    System.out.println("Error occurred in MulticastTask during receive(): "+e);
                }
                String s = new String(packet.getData(), StandardCharsets.US_ASCII);
                String buf = null;
                if(reader.isReading()) {
                    buf = reader.getBuffer().toString();
                }
                lock.lock();
                System.out.println("\n"+ANSI_GREEN+s+ANSI_RESET);
                System.out.println(ANSI_CYAN+"Insert a command:"+ANSI_RESET);
                System.out.print(buf);
                lock.unlock();
            }
            try {
                multicastSocket.leaveGroup(mcastGroupSocketAddress, netIf);
            }catch (IOException e) {
                System.out.println("Error occurred in leaveGroup() in MulticastTask: "+e);
            }
            multicastSocket.close();
            return;
        }
    }
}
