import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import javax.xml.stream.events.Characters;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RemoteObject;
import java.rmi.server.UnicastRemoteObject;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ServerMain implements RegisterService{

    //Colors used in prints to distinguish different operation
    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_PURPLE = "\u001B[35m";
    public static final String ANSI_BLUE = "\u001B[34m";
    public static final String ANSI_GREEN = "\u001B[32m";

    public static final String CONFIG_FILE = "./../config/CONFIG_Server.txt";  //Name of CONFIG file for server
    private static final String RANDOM_ORG = "https://www.random.org/decimal-fractions/?num=1&dec=10&col=1&format=plain&rnd=new";

    private static List<User> Users; //Users who are part of Winsom Social Media
    private static List<String> loggedUsers; //Users who are now logged in Winsome
    private static ConcurrentHashMap<String, List<String>> followersMap; //list of the user who user with username "key" follows NOT NECESSARY
    private static ConcurrentHashMap<String, List<String>> followingMap; //list of followers for user with username "key"
    private static List<Post> Posts; //Posts who are in Winsom Social Media
    private static ConcurrentHashMap<Socket, String> clientsConnected; //TCP connection with username's client logged to the server
    private static String SERVERADDRESS;  //Server address (unused in ServerMain)
    private static int TCPPORT;   //TCP port
    private static String MULTICASTADDRESS;   //Multicast address
    private static int MCASTPORT; //Multicast port
    private static String REGHOST;   //RMI registry host
    private static int REGPORT;   //RMI registry port
    private static int REGPORTCB; //RMI Callback registry port
    private static int TIMEOUTSOCKET; //Time to wait (with no requests) before closing the socket connection
    private static int TIMEOUTREWARDS;    //Interval between the calculation of two rewards
    private static int AUTHORREWARDSPERCENTAGE;//Percentage that author of the post obtains
    private static int TIMEOUTSTORAGE; //Interval between two write in storage files
    private static String USERSFILE;   //RMI registry host
    private static String POSTSFILE;   //RMI registry host
    private static ServerMain serverMainObj;    //Implementation class for rmi registry
    private static ServerNotifyImpl serverNotify;   //Implementation class for rmi callback
    private static Registry registry;   //Registry for register
    private static Registry registryCallBack;   //Registry for follow callback

    private static final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private static final Lock readLock = readWriteLock.readLock();
    private static final Lock writeLock = readWriteLock.writeLock();

    public ServerMain(){
        this.Users = new CopyOnWriteArrayList<User>();
        this.loggedUsers = new CopyOnWriteArrayList<String>();
        this.followersMap = new ConcurrentHashMap<String, List<String>>();
        this.followingMap = new ConcurrentHashMap<String, List<String>>();
        this.Posts = new CopyOnWriteArrayList<Post>();
        this.clientsConnected = new ConcurrentHashMap<Socket, String>();
    }

    public static void main(String[] args) throws IOException{
        if(args.length != 0){
            System.out.println("Usage: java -cp .:../gson-2.8.9.jar ServerMain");
            System.exit(-1);
        }
        else{
            boolean correctRead;
            correctRead = readConfigFile(CONFIG_FILE);
            if(!correctRead)
                System.exit(-1);
        }

        //RMI Building
        try{
            //RMI Registry
            LocateRegistry.createRegistry(REGPORT);
            registry = LocateRegistry.getRegistry(REGHOST, REGPORT);
            //Instantiating the implementation class
            serverMainObj = new ServerMain();
            //Exporting the remote object to the stub
            RegisterService stub = (RegisterService) UnicastRemoteObject.exportObject(serverMainObj, REGPORT);
            //Binding the remote object (stub) in the registry named "RegisterService
            registry.bind("RegisterService", stub);
            //RMI Callback
            LocateRegistry.createRegistry(REGPORTCB);
            registryCallBack = LocateRegistry.getRegistry(REGHOST, REGPORTCB);
            //Instantiating the implementation class
            serverNotify = new ServerNotifyImpl();
            //Exporting the remote object to the stub
            ServerNotifyInterface stubNotify = (ServerNotifyInterface) UnicastRemoteObject.exportObject(serverNotify, REGPORTCB); //or someone else port
            //Binding the remote object (stubNotify) in the registry named "ServerNotify"
            registryCallBack.bind("ServerNotify", stubNotify);
        }catch(Exception e){
            System.err.println("Server exception: " + e.toString());
            e.printStackTrace();
            return;
        }

        //Restore state of Server
        writeLock.lock();
        restoreStorage();
        writeLock.unlock();

        //Thread who stores the state of the server
        //with an interval of TIMEOUTSTORAGE milliseconds
        Thread timeoutStorageThread = new Thread(new TimeOutStorage());
        timeoutStorageThread.setDaemon(true);
        timeoutStorageThread.start();

        //Register the Shutdown Thread
        Runtime.getRuntime().addShutdownHook(new ShutDown());

        //Thread who waits for ":q!" to terminate server process
        Thread closeServerThread = new Thread(new CloseServer());
        closeServerThread.setDaemon(true);
        closeServerThread.start();

        //Thread who handle the multicast group (reward for the clients)
        Thread rewardMulticastThread = new Thread(
                new RewardMulticastTask(MULTICASTADDRESS, MCASTPORT, TIMEOUTREWARDS, AUTHORREWARDSPERCENTAGE)
        );
        rewardMulticastThread.setDaemon(true);
        rewardMulticastThread.start();

        //Starting thread to check the TCP's connections and logout killed connections
        try{
            Thread checkConnThread = new Thread(new ConnectionHandler());
            checkConnThread.setDaemon(true);
            checkConnThread.start();
        }catch (IllegalThreadStateException itse){
            System.out.println("Error starting ConnectionHandler Thread: "+itse);
            System.exit(-1);
        }
        //ThreadPool Building
        ExecutorService service = Executors.newCachedThreadPool(); //unbounded number of threads

        //TCP connection building (try-with-resources : close at the end)
        try(ServerSocket server = new ServerSocket(TCPPORT);
        ){
            server.setSoTimeout(TIMEOUTSOCKET);
            server.setReuseAddress(true); //to reuse socket after a timeout
            System.err.println(ANSI_RED+"Server ready!"+ANSI_RESET); //Debug
            while(true){
                //receive the connection and start thread for ClientHandler
                //System.out.println("I'm in while(true) loop in server before accept");  //DEBUG
                Socket client = server.accept();
                //System.out.println("New client connected at the address "+client.getInetAddress().getHostAddress()); //DEBUG
                service.execute(new ClientHandler(client)); //to start new Thread handler
            }
        }catch(SocketTimeoutException ste){
            System.out.println("Socket timeout reached : "+ste);
            service.shutdown(); //To Interrupt the ThreadPool
        }catch(IOException e){
            System.out.println("ERROR: In while loop in ServerMain "+e);
            System.exit(-1);
        }finally {
            if(readWriteLock.isWriteLockedByCurrentThread()){
                writeLock.unlock();
            }
        }
        return;
    }

    //method to restore the state of the Server
    //from files UsersStorage.txt and PostsStorage.txt
    public static void restoreStorage(){
        Storage storage = new Storage();
        //Now read from file JSON.txt and put into structures
        boolean newFiles = false;
        try{
            File myFile = new File(USERSFILE);
            if(myFile.createNewFile()){
                newFiles = true;
                //System.out.println("File 'UsersStorage.txt' created!");  //DEBUG
            }else{
                //System.out.println("File 'UsersStorage.txt' already exists");  //DEBUG
            }
        }catch(IOException e){
            System.out.println("Error occured creating new file 'UsersStorage.txt' : "+e);
            e.printStackTrace();
        }
        try{
            File myFile = new File(POSTSFILE);
            if(myFile.createNewFile()){
                newFiles = true;
                //System.out.println("File 'PostsStorage.txt' created!");  //DEBUG
            }else{
                //System.out.println("File 'PostsStorage.txt' already exists");  //DEBUG
            }
        }catch(IOException e){
            System.out.println("Error occured creating new file 'PostsStorage.txt' : "+e);
            e.printStackTrace();
        }
        if(newFiles){  //case of json files are created now
            try (PrintWriter outUsers = new PrintWriter(USERSFILE);
                 PrintWriter outPosts = new PrintWriter(POSTSFILE))
            {
                outUsers.write("[]");
                outPosts.write("[]");
            }catch (FileNotFoundException e){
                System.out.println("Error writing in files : "+e);
                e.printStackTrace();
                System.exit(-1);
            }

        }
        try(InputStream JSONfileUsers = new FileInputStream(USERSFILE);
            InputStream JSONfilePosts = new FileInputStream(POSTSFILE);)
        {
            writeLock.lock();
            //System.out.println("Start of readJsonStream..."); //DEBUG
            storage.readJsonStream(JSONfileUsers, JSONfilePosts);
            writeLock.unlock();
        }catch(IOException e){
            System.out.println("Error occured using json");
            e.printStackTrace();
        }
    }

    //method to save the state of the Server
    //into files UsersStorage.txt and PostsStorage.txt
    public static void storeStorage(){
        Storage storage = new Storage();
        //Now read from structures and put into file JSON_W.txt
        try{
            File myFile = new File(USERSFILE);
            if(myFile.createNewFile()){
                //System.out.println("File 'UsersStorage.txt' created!");  //DEBUG
            }else{
                //System.out.println("File 'UsersStorage.txt' already exists");  //DEBUG
            }
        }catch(IOException e){
            System.out.println("Error occured creating new file 'UsersStorage.txt' : "+e);
            e.printStackTrace();
        }
        try{
            File myFile = new File(POSTSFILE);
            if(myFile.createNewFile()){
                //System.out.println("File 'PostsStorage.txt' created!");  //DEBUG
            }else{
                //System.out.println("File 'PostsStorage.txt' already exists");  //DEBUG
            }
        }catch(IOException e){
            System.out.println("Error occured creating new file 'PostsStorage.txt' : "+e);
            e.printStackTrace();
        }
        try(OutputStream JSON_WUsersfile = new FileOutputStream(USERSFILE);
            OutputStream JSON_WPostsfile = new FileOutputStream(POSTSFILE);)
        {
            writeLock.lock();
            //System.out.println("Start of writeJsonStream...");  //DEBUG
            storage.writeJsonStream(JSON_WUsersfile, JSON_WPostsfile);
            writeLock.unlock();
        }catch (IOException e){
            System.out.println("Error occured creating new OutputStreams : "+e);
            e.printStackTrace();
        }
    }

    //generate the next post Id
    //return the id of last post generated plus one
    public static int postIdGenerator(){
        if(Posts.size() == 0){
            return 0;
        }
        return (Posts.get(Posts.size()-1).getId()) + 1;
    }

    //Calling RMI method with the client stub
    public String register(String username, String password, List<String> tags)
            throws RemoteException{
        if(username == null || password == null || tags == null){
            return "ERROR: Register command has few arguments";
            //throw new NullPointerException();
        }
        writeLock.lock();
        if(usernameExistingYet(username)){
            writeLock.unlock();
            return "ERROR: Username existing in Winsome Social Media yet";
        }
        if(username.isEmpty()){
            writeLock.unlock();
            return "ERROR: Username field is empty.";
        }
        if(password.isEmpty()){
            writeLock.unlock();
            return "ERROR: Password field is empty.";
        }
        if(tags.isEmpty()){
            writeLock.unlock();
            return "ERROR: No tags in user profile registration";
        }
        if(tags.size() > 5){
            writeLock.unlock();
            return "ERROR: Too much tags in user profile registration (max 5 tags permitted)";
        }
        if(containsDuplicateTags(tags)) {
            writeLock.unlock();
            return "ERROR: Duplicate tags in user profile registration";
        }
        //Parameters correctly given, user registered
        User userToAdd = new User(username, password, tags);
        (this.Users).add(userToAdd);
        (this.followersMap).putIfAbsent(username, new ArrayList<String>());
        (this.followingMap).putIfAbsent(username, new ArrayList<String>());
        writeLock.unlock();
        return "User '" +username+ "' registered!";
    }

    //return true if the 'tags' list contains an element twice
    public boolean containsDuplicateTags(List<String> tags){
        List<String> tmpTags = new ArrayList<String>();
        for(int i=0; i<tags.size(); i++){
            if(tmpTags.contains(tags.get(i))){
                return true;
            }
            tmpTags.add(tags.get(i));
        }
        return false;
    }

    /**
     *  Say if a username existing yet in registered users
     * @param username
     * @return  *Do the same thing of userIsReg(username)*
     */
    public boolean usernameExistingYet(String username){
        return userIsReg(username);
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
            case "TIMEOUT":
                TIMEOUTSOCKET = Integer.parseInt(strArr[1]);
                break;
            case "TIMEOUTREWARDS":
                TIMEOUTREWARDS = Integer.parseInt(strArr[1]);
                break;
            case "AUTHORREWARD":
                AUTHORREWARDSPERCENTAGE = Integer.parseInt(strArr[1]);
                break;
            case "TIMEOUTSTORAGE":
                TIMEOUTSTORAGE = Integer.parseInt(strArr[1]);
                break;
            case "USERSFILE":
                USERSFILE = strArr[1];
                break;
            case "POSTSFILE":
                POSTSFILE = strArr[1];
                break;
            default:
                System.out.println("Unknown NAME in file CONFIG_Server.txt");
                System.out.println("Error setting Server parameters... ERROR FAILURE");
                System.exit(-1);
        }
    }

    /**
     * Say if a user with username 'username' is registered to Winsome
     * @param username
     * @return true if Users.get(i).contains(username) for-each i from 0 to Users.size()
     * @throws NullPointerException
     */
    public static boolean userIsReg(String username) throws NullPointerException{
        if(username == null){
            throw new NullPointerException();
        }
        boolean exists = false;
        Iterator<User> iterUsers = Users.iterator();
        while(iterUsers.hasNext()){
            if((iterUsers.next()).getUsername().equals(username)){
                exists = true;
            }
        }
        return exists;
    }



    public static class ClientHandler implements Runnable{
        public Socket clientSocket;
        public String username;

        public ClientHandler(Socket client) throws IOException{
            this.clientSocket = client;
            this.username = ""; //UNUSED
        }

        public void run(){
            System.out.println(ANSI_GREEN+"A new ClientHandler task is now running!"+ANSI_RESET);  //DEBUG
            //System.out.println("I'm in the running thread n° "+getIndClient()); //DEBUG
            setClientsConnected(clientSocket, "");

            String clientResponse;
            try(BufferedReader input = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                PrintWriter output = new PrintWriter(clientSocket.getOutputStream(), true))
            {
                while (true) {
                    String lineReceived = input.readLine();
                    //System.out.println("Received from Client: " + lineReceived);  //DEBUG
                    if(lineReceived == null){ //HANDLE TERMINATION OF A CLIENT THREAD
                        String str = logout(this.username);
                        //System.out.println(ANSI_RED+"Exit caused by client kill..."+ANSI_RESET);  //DEBUG
                        //System.out.println(ANSI_RED+str+ANSI_RESET);  //DEBUG
                        return;
                    }
                    clientResponse = readCommandAndCall(lineReceived);
                    output.println(clientResponse);
                    output.flush();
                    //System.out.println(ANSI_BLUE+clientResponse+ANSI_RESET); //DEBUG
                    //System.out.println("I'm at the END of while");  //DEBUG
                }
            }catch(IOException e){
                System.out.println("In client "+clientSocket.getInetAddress().toString());
                System.out.println("ERROR in read() in run method: "+e);
            }
        }

        public Socket getIndClient(){
            return this.clientSocket;
        }

        public String getUsernameLogged(){
            return this.username;
        }

        public void setUsernameLogged(String usernameLog){
            this.username = usernameLog;
        }

        /**
         * Say if a user with username 'username' is logged to Winsome
         * @param username
         * @return true if loggedUsers.contains(username)
         * @throws NullPointerException
         */
        public boolean userIsLogged(String username) throws NullPointerException{
            if(username == null){
                throw new NullPointerException();
            }
            boolean isLogged = false;
            if(loggedUsers.contains(username))
                isLogged = true;
            return isLogged;
        }

        /**
         * Check if password is correct for the user with username 'username'
         * @param username
         * @param password
         * @return true if (Users.get(i).getPassword()).equals(password)
         */
        public boolean logCheckOver(String username, String password){
            boolean isCorrect = false;
            User user = null;
            Iterator<User> iterUsers = Users.iterator();
            while(iterUsers.hasNext()){
                user = iterUsers.next();
                if((user.getUsername()).equals(username)){
                    if(user.comparePassword(password)){
                        isCorrect = true;
                    }
                    else{
                        isCorrect = false;
                    }
                    break;
                }
            }
            return isCorrect;
        }

        public List<User> makeFollowersUsers(List<String> followers){
            List<User> followersUsers = new ArrayList<User>();
            Iterator<String> followerIterator = followers.iterator();
            while(followerIterator.hasNext()){
                followersUsers.add(getUser(followerIterator.next()));
            }
            return followersUsers;
        }

        public List<User> makeFollowingUsers(List<String> following){
            List<User> followingUsers = new ArrayList<User>();
            Iterator<String> followingIterator = following.iterator();
            while(followingIterator.hasNext()){
                followingUsers.add(getUser(followingIterator.next()));
            }
            return followingUsers;
        }

        //method called by followUser() method
        //add newFollower to the list associated to userFollowed key in followersMap
        public void addFollowerAndNotify(String userFollowed, User newFollower){
            followersMap.putIfAbsent(userFollowed, new ArrayList<String>());
            List<String> listUserFollowers = followersMap.get(userFollowed);
            listUserFollowers.add(newFollower.getUsername());
            followersMap.replace(userFollowed, listUserFollowers);
            try {
                serverNotify.update(userFollowed, listUserFollowers, newFollower);
            }catch(RemoteException e){
                System.out.println("Exception during RMI Callback update: "+e);
            }
        }

        //method called by unfollowUser() method
        //remove newFollower to the list associated to userFollowed key in followersMap
        public void removeFollowerAndNotify(String userFollowed, User oldFollower){
            List<String> listUserFollowers = followersMap.get(userFollowed);
            listUserFollowers.remove(oldFollower.getUsername());
            followersMap.replace(userFollowed, listUserFollowers);
            try {
                List<User> followersUsers = makeFollowersUsers(listUserFollowers);
                serverNotify.remove(userFollowed, followersUsers, oldFollower);
            }catch(RemoteException e){
                System.out.println("Exception during RMI Callback update: "+e);
            }
        }

        //Auxiliary method for followUser() and unfollowUser() functions
        //return true if logged user is in "users" list
        public boolean isFollowingYet(String username){
            List<String> followersList = followingMap.get(getUsernameLogged());
            User tmpUser = null;
            boolean isFollowing = false;
            Iterator<String> iter = followersList.iterator();
            while(iter.hasNext()){
                tmpUser = getUser(iter.next());
                if((tmpUser.getUsername()).equals(username)){
                    isFollowing = true;
                    break;
                }
            }
            return isFollowing;
        }

        //Auxiliary method for unfollow() method
        //Remove retwetter (user logged) from post with user 'idUser'
        public void removeRetweeterFromPosts(String idUser){
            Post tmpPost = null;
            List<String> retweeters = null;
            for(int i=0; i<Posts.size(); i++){
                tmpPost = Posts.get(i);
                if(tmpPost.getAuthor().equals(idUser)){
                    retweeters = tmpPost.getRetweeters();
                    retweeters.remove(getUsernameLogged());
                    tmpPost.setRetweeters(retweeters);
                }
            }
        }

        //Prepare the string to show in showPost() method
        public String makeStringShowPost(Post post){
            String newLine = System.lineSeparator();
            String str = "Title: "+post.getTitle()+newLine;
            str = str.concat("Content: "+post.getContent()+newLine);
            str = str.concat("Votes: "+post.getLikesNum()+" positivi, "+
                    post.getDislikesNum()+" negativi"+newLine);
            str = str.concat("Comments:");
            if(post.getComments().size() == 0){
                str = str.concat(" "+0+newLine);
            }
            else{
                str = str.concat(newLine);
                List<Comment> comments = post.getComments();
                for(int i=0; i<comments.size(); i++){
                    str = str.concat("\t"+comments.get(i).getAuthor()+": "
                            +"\""+comments.get(i).getText()+"\""+newLine);
                }
            }
            return str;
        }

        public String makeCommentcomment(String strArr[]){
            String[] strListSplitted = null;
            String strToReturn = null;
            String str = "";
            for(int i=2; i<strArr.length-1; i++){
                str = str.concat(strArr[i]+" ");
            }
            str = str.concat(strArr[strArr.length-1]);
            str = str.strip();
            if(!containTwoQuotationMark(str)){
                return strToReturn;
            }
            //System.out.println("str : "+str); //DEBUG LINEEEE!!!
            strListSplitted = str.split("\"");
            int noWhiteSpaceCount = 0;
            int j = 0;
            for(int i=0; i<strListSplitted.length; i++){
                if(!(isWhiteSpaceString(strListSplitted[i]))){
                    noWhiteSpaceCount++;
                    if(j<1){
                        strToReturn = strListSplitted[i];
                        j++;
                    }
                }
            }
            if(noWhiteSpaceCount > 1){
                strToReturn = null;
                return strToReturn;
            }
            return strToReturn;
        }

        //some a the next methods are auxiliary method for 'post' command
        public String[] makePostTitleAndContent(String strArr[]){
            String[] strListSplitted = null;
            String[] strListToReturn = new String[2];
            String str = "";
            for(int i=1; i<strArr.length-1; i++){
                str = str.concat(strArr[i]+" ");
            }
            str = str.concat(strArr[strArr.length-1]);
            str = str.strip();
            if(!containFourQuotationMark(str)){
                return strListToReturn;
            }
            //System.out.println("str : "+str); //DEBUG LINEEEE!!!
            strListSplitted = str.split("\"");
            int noWhiteSpaceCount = 0;
            int j = 0;
            for(int i=0; i<strListSplitted.length; i++){
                if(!(isWhiteSpaceString(strListSplitted[i]))){
                    noWhiteSpaceCount++;
                    if(j<2){
                        strListToReturn[j] = strListSplitted[i];
                        j++;
                    }
                }
            }
            if(noWhiteSpaceCount > 2){
                strListToReturn[0] = null;
                return strListToReturn;
            }
            return strListToReturn;
        }

        //Auxiliary method for makePostTitleAndContent()
        //check if this string contain four quotation marks
        public boolean containFourQuotationMark(String str){
            int quotationMarkCount = 0;
            for(int i=0; i<str.length(); i++){
                if(str.charAt(i) == 34){
                    quotationMarkCount++;
                }
            }
            return quotationMarkCount == 4;
        }

        //Auxiliary method for makePostTitleAndContent()
        //check if this string contain four quotation marks
        public boolean containTwoQuotationMark(String str){
            int quotationMarkCount = 0;
            for(int i=0; i<str.length(); i++){
                if(str.charAt(i) == 34){
                    quotationMarkCount++;
                }
            }
            return quotationMarkCount == 2;
        }

        //Auxiliary method for makePostTitleAndContent()
        //return true if the argument is an empty string
        public boolean isWhiteSpaceString(String str){
            for(int i=0; i<str.length(); i++){
                if(!(Character.isWhitespace(str.charAt(i)))){
                    return false;
                }
            }
            return true;
        }

        //return true if post command was bad formatted, false otherwise
        public boolean postArgsAreBadFormatted(String[] titleAndContent){
            if(!(titleAndContent[0] != null && titleAndContent[1] != null)){
                return true;
            }
            if(!(!isWhiteSpaceString(titleAndContent[0]) &&
                    !isWhiteSpaceString(titleAndContent[1]))){
                return true;
            }
            return false;
        }

        //return true if comment command was bad formatted, false otherwise
        public boolean commentArgIsBadFormatted(String comment){
            if(comment == null) {
                return true;
            }
            if(isWhiteSpaceString(comment)){
                return true;
            }
            return false;
        }

        //USEFUL METHOD (maybe used when blog data structure existed)
        public int getPostListIndex(int postId, List<Post> postList){
            int indexList = -1;
            for(int i=0; i<postList.size(); i++){
                if(postList.get(i).getId() == postId){
                    indexList = i;
                    break;
                }
            }
            return indexList;
        }

        public String login(String username, String password) throws RemoteException{
            writeLock.lock();
            if(!userIsReg(username)){
                writeLock.unlock();
                return "Error: user '"+username+"' doesn't exist";
            }
            if(userIsLogged(username)){
                writeLock.unlock();
                return "Error: there is a user logged yet, must be before logged out";
            }
            if(!logCheckOver(username, password)){
                writeLock.unlock();
                return "Error: password for user '"+username+"' is incorrect";
            }
            setUsernameLogged(username);
            loggedUsers.add(username);
            clientsConnected.put(getIndClient(), username);
            writeLock.unlock();
            return username+" logged in";
        }

        public String logout(String username){
            String strToReturn = null;
            writeLock.lock();
            if(!loggedUsers.contains(username)) {
                strToReturn = username+" isn't logged";
            }
            else{
                loggedUsers.remove(username);
                clientsConnected.replace(getIndClient(), "");
                setUsernameLogged("");
                strToReturn = username+" logged out";
            }
            writeLock.unlock();
            return strToReturn;
        }

        public String listUsers(){
            String newLine = System.lineSeparator(); //newline character
            String toReturn = new String("User \t\t|\tTag"+newLine);
            toReturn = toReturn.concat("------------------------------------"+newLine);
            User requiringUser; //User who require the list
            String usernameUser = null;//username of the requiring user
            List<String> tagsUser = null; //tags of the requiringUser
            readLock.lock();
            Iterator<User> iterUsers = Users.iterator();
            while(iterUsers.hasNext()){
                requiringUser = iterUsers.next();
                if((requiringUser).getUsername().equals(getUsernameLogged())){
                    usernameUser = requiringUser.getUsername();
                    tagsUser = requiringUser.getTags();
                    break;
                }

            }
            User tmpUser; //User who have some common tag with requiringUser
            List<String> tmpTags = null; //tags of the User above
            boolean equalsTags;
            iterUsers = Users.iterator();
            while(iterUsers.hasNext()){
                tmpUser = iterUsers.next();
                if(!(tmpUser.getUsername().equals(usernameUser))){
                    equalsTags = false;
                    tmpTags = tmpUser.getTags();
                    for(int i=0; i<tmpTags.size(); i++){
                        if(tagsUser.contains(tmpTags.get(i))){
                            equalsTags = true;
                            break;
                        }
                    }
                    //perform the string with the new line to show
                    if(equalsTags){
                        toReturn = toReturn.concat(tmpUser.getUsername()+"\t\t|\t");
                        for(int i=0; i<tmpTags.size()-1; i++){
                            toReturn = toReturn.concat(tmpTags.get(i)+", ");
                        }
                        toReturn = toReturn.concat(tmpTags.get(tmpTags.size()-1)+newLine);
                    }
                }
            }
            readLock.unlock();
            return toReturn;
        }

        public String listFollowing(){
            String newLine = System.lineSeparator(); //newline character
            String strUserToCheck = null;
            User userToAdd = null;
            String strToReturn = new String("User \t\t|\tTag"+newLine);
            strToReturn = strToReturn.concat("-----------------------------"+newLine);

            readLock.lock();
            List<User> listFollowing = makeFollowingUsers(followingMap.get(getUsernameLogged()));
            Iterator<User> iter = listFollowing.iterator();
            readLock.unlock();

            while(iter.hasNext()){
                userToAdd = iter.next();
                strToReturn = strToReturn.concat(userToAdd.getUsername()+"\t\t|\t");
                List<String> userTags = userToAdd.getTags();
                for(int i=0; i<userTags.size()-1; i++){
                    strToReturn = strToReturn.concat(userTags.get(i)+", ");
                }
                strToReturn = strToReturn.concat(userTags.get(userTags.size()-1)+newLine);
            }

            return strToReturn;
        }

        public String followUser(String idUser) throws RemoteException{
            if(idUser.equals(getUsernameLogged())){
                return "Ah ah ah ah... Cannot follow yourself.";
            }
            writeLock.lock();
            if(!userIsReg(idUser)){
                writeLock.unlock();
                return "Error: user with username "+idUser+" doesn't exist.";
            }
            if(isFollowingYet(idUser)){
                writeLock.unlock();
                return "You follow user '"+idUser+"' yet.";
            }
            //Check if there's some common tags
            User tmpUser = getUser(idUser);
            User reqUser = getUser(getUsernameLogged());
            List<String> tmpUserTags = tmpUser.getTags();
            List<String> reqUserTags = reqUser.getTags();
            boolean equalsTags = false;
            for(int i=0; i<tmpUserTags.size(); i++){
                if(reqUserTags.contains(tmpUserTags.get(i))){
                    equalsTags = true;
                    break;
                }
            }
            if(!equalsTags){
                writeLock.unlock();
                return "Error: tags of user with username "+idUser+" don't match.";
            }
            //If everything going well it puts the new user to followingMap
            String userLogged = getUsernameLogged();
            List<String> tmpUsersList = followingMap.get(getUsernameLogged());
            tmpUsersList.add(tmpUser.getUsername());
            followingMap.replace(userLogged, tmpUsersList);
            addFollowerAndNotify(tmpUser.getUsername(), getUser(userLogged));
            writeLock.unlock();
            return "Now you follow '"+idUser+"'!";
        }

        public String unfollowUser(String idUser) throws RemoteException{
            if(idUser.equals(getUsernameLogged())){
                return "Ah ah ah ah... Cannot unfollow yourself.";
            }
            writeLock.lock();
            if(!isFollowingYet(idUser)){
                writeLock.unlock();
                return "You weren't following user '"+idUser+"'.";
            }
            //If everything going well it remove the new user from followingMap
            String userLogged = getUsernameLogged();
            User tmpUser = getUser(idUser);
            List<String> tmpUsersList = followingMap.get(getUsernameLogged());
            tmpUsersList.remove(tmpUser.getUsername());
            followingMap.replace(userLogged, tmpUsersList);
            removeRetweeterFromPosts(idUser);
            removeFollowerAndNotify(tmpUser.getUsername(), getUser(userLogged));
            writeLock.unlock();
            return "You have stopped following '"+idUser+"'";
        }

        public String viewBlog(){
            String newLine = System.lineSeparator(); //newline character
            String userRequiring = getUsernameLogged();
            Post postToAdd = null;
            String strToReturn = new String("Id \t\t|\tAuthor \t\t|\tTitle"+newLine);
            strToReturn = strToReturn.concat("---------------------------------------------------------"+newLine);
            List<Post> listBlogPosts = new ArrayList<Post>();

            readLock.lock();
            for(int i=0; i<Posts.size(); i++){
                postToAdd = Posts.get(i);
                List<String> retweeters = postToAdd.getRetweeters();
                if(!listBlogPosts.contains(postToAdd)){
                    if(postToAdd.getAuthor().equals(userRequiring) ||
                        retweeters.contains(userRequiring)){
                        listBlogPosts.add(postToAdd);
                    }
                }
            }
            Iterator<Post> iter = listBlogPosts.iterator();
            readLock.unlock();

            while(iter.hasNext()){
                postToAdd = iter.next();
                strToReturn = strToReturn.concat(postToAdd.getId()+"\t\t|\t");
                strToReturn = strToReturn.concat(postToAdd.getAuthor()+"\t\t|\t");
                strToReturn = strToReturn.concat("\""+postToAdd.getTitle()+"\""+newLine);
            }
            return strToReturn;
        }

        public String createPost(String title, String content){
            if(title.length()>20){
                return "The title for this post is too long (max 20 characters)";
            }
            if(content.length()>500){
                return "The content for this post is too long (max 500 characters)";
            }
            writeLock.lock();
            int postId = postIdGenerator();
            Post newPost = new Post(postId, title, content, getUsernameLogged());
            Posts.add(newPost);
            writeLock.unlock();
            return "New post has been created successfully (id="+postId+")";
        }

        public String showFeed(){
            boolean found = false;
            String newLine = System.lineSeparator(); //newline character
            String userRequiring = getUsernameLogged();
            Post postToAdd = null;
            String strToReturn = new String("Id \t\t|\tAuthor \t\t|\tTitle"+newLine);
            strToReturn = strToReturn.concat("---------------------------------------------------------"+newLine);
            List<Post> listFeedPosts = new ArrayList<Post>();

            readLock.lock();
            for(int i=0; i<Posts.size(); i++){
                found = false;
                postToAdd = Posts.get(i);
                List<String> retweeters = postToAdd.getRetweeters();
                if(!listFeedPosts.contains(postToAdd)){
                    if(isFollowingYet(postToAdd.getAuthor())){
                        listFeedPosts.add(postToAdd);
                        found = true;
                    }
                    for(int j=0; !found && j<retweeters.size(); j++){
                        if(isFollowingYet(retweeters.get(j))){
                            listFeedPosts.add(postToAdd);
                            found = true;
                        }
                    }
                }
            }
            Iterator<Post> iter = listFeedPosts.iterator();
            readLock.unlock();

            while(iter.hasNext()){
                postToAdd = iter.next();
                strToReturn = strToReturn.concat(postToAdd.getId()+"\t\t|\t");
                strToReturn = strToReturn.concat(postToAdd.getAuthor()+"\t\t|\t");
                strToReturn = strToReturn.concat("\""+postToAdd.getTitle()+"\""+newLine);
            }
            return strToReturn;
        }

        public String showPost(int idPost){
            boolean postExists = false;
            Post tmpPost = null;
            String postAuthor = null;
            readLock.lock();
            for(int i=0; i<Posts.size(); i++){
                if(Posts.get(i).getId() == idPost){
                    tmpPost = Posts.get(i);
                    postAuthor = tmpPost.getAuthor();
                    if(postAuthor.equals(getUsernameLogged()) || isFollowingYet(postAuthor)) {
                        postExists = true;
                    }
                    break;
                }
            }
            if(!postExists){
                return "Post with id '"+idPost+"' doesn't exist.";
            }
            String strToReturn = makeStringShowPost(tmpPost);
            readLock.unlock();
            return strToReturn;
        }

        public String deletePost(int idPost){
            String strToReturn = null;
            Post tmpPost = null;
            writeLock.lock();
            for(int i=0; i<Posts.size(); i++){
                tmpPost = Posts.get(i);
                if(tmpPost.getId()==idPost){
                    if(!tmpPost.getAuthor().equals(getUsernameLogged())){
                        strToReturn = "Cannot delete this post! You are not the author.";
                        writeLock.unlock();
                        return strToReturn;
                    }
                    Posts.remove(i);
                    strToReturn = "The post with id '" +idPost+ "' has been deleted";
                    break;
                }
            }
            writeLock.unlock();
            return strToReturn;
        }

        public String rewinPost(int idPost){
            boolean postExists = false;
            String strToReturn = null;
            String authorPost = null;
            Post tmpPost = null;
            String retweeter = getUsernameLogged();
            writeLock.lock();
            for(int i=0; i<Posts.size(); i++){
                tmpPost = Posts.get(i);
                if(tmpPost.getId()==idPost){
                    postExists = true;
                    authorPost = tmpPost.getAuthor();
                    if(authorPost.equals(retweeter)){
                        strToReturn = "Cannot rewin this post! You are the author.";
                        break;
                    }
                    if(!isFollowingYet(authorPost)){
                        strToReturn = "Cannot rewin this post! Post isn't in your feed.";
                        break;
                    }
                    if(tmpPost.getRetweeters().contains(retweeter)){
                        strToReturn = "Cannot rewin this post again! You already rewin this post";
                        break;
                    }
                    tmpPost.addRetweeter(retweeter);
                    Posts.set(i, tmpPost);
                    strToReturn = "The post with id '" +idPost+ "' is now in your blog";
                    break;
                }
            }
            if(!postExists){
                strToReturn = "Cannot rewin this post! It doesn't exist.";
            }
            writeLock.unlock();
            return strToReturn;
        }

        public String ratePost(int idPost, int voto){
            boolean voted = false;
            String strToReturn = null;
            Post tmpPost = null;
            writeLock.lock();
            for(int i=0; !voted && i<Posts.size(); i++){
                tmpPost = Posts.get(i);
                if(tmpPost.getId() == idPost){
                    if(isFollowingYet(tmpPost.getAuthor())){
                        if(voto>0) {
                            voted = tmpPost.addLike(getUsernameLogged());
                            if(voted) {
                                strToReturn = "The post has been voted with '+1' value";
                            }else{
                                strToReturn = "Cannot vote again! You already had voted for the post.";
                            }
                        }
                        else{
                            voted = tmpPost.addDislike(getUsernameLogged());
                            if(voted) {
                                strToReturn = "The post has been voted with '-1' value";
                            }else{
                                strToReturn = "Cannot vote again! You already had voted for the post.";
                            }
                        }
                        if(voted) {
                            Posts.set(i, tmpPost);
                        }
                    }
                    else{
                        if(tmpPost.getAuthor().equals(getUsernameLogged())){
                            strToReturn = "Cannot vote your posts!";
                        }
                        else{
                            strToReturn = "Cannot vote a post that isn't in your feed!";
                        }
                    }
                }
            }
            writeLock.unlock();
            return strToReturn;
        }

        public String addComment(int idPost, String comment){
            boolean postExists = false;
            String strToReturn = null;
            String authorPost = null;
            Post tmpPost = null;
            String commenter = getUsernameLogged();
            writeLock.lock();
            for(int i=0; i<Posts.size(); i++){
                tmpPost = Posts.get(i);
                if(tmpPost.getId()==idPost){
                    postExists = true;
                    authorPost = tmpPost.getAuthor();
                    if(authorPost.equals(commenter)){
                        strToReturn = "Cannot comment this post! You are the author.";
                        break;
                    }
                    if(!isFollowingYet(authorPost)){
                        strToReturn = "Cannot comment this post! Post isn't in your feed.";
                        break;
                    }
                    tmpPost.addComment(commenter, comment);
                    Posts.set(i, tmpPost);
                    //fixAuthorAndRetweetersBlogPosts(tmpPost);
                    strToReturn = "The post with id '" +idPost+ "' has been commented";
                    break;
                }
            }
            if(!postExists){
                strToReturn = "Cannot comment this post! It doesn't exist.";
            }
            writeLock.unlock();
            return strToReturn;
        }

        public String getWallet(){
            String newLine = System.lineSeparator();
            DecimalFormat df = new DecimalFormat("#.########");
            //df.format(wallet.getWincoin());
            String str = null;
            User user = null;
            Wallet wallet = null;
            List<Transaction> transactions = null;
            readLock.lock();
            user = getUser(getUsernameLogged());
            wallet = user.getWallet();
            readLock.unlock();
            str = "Wallet of '"+getUsernameLogged()+"':"+newLine;
            str = str.concat("\t-Total Amount: \t"+df.format(wallet.getWincoin())+"wc"+newLine);
            str = str.concat("\t-Transactions History:"+newLine);
            transactions = wallet.getTransactions();
            for(int i=0; i<transactions.size(); i++){
                str = str.concat("\t\t"+(i+1)+". +"+df.format((transactions.get(i)).getAmount())+"wc  "+
                        "("+(transactions.get(i)).getTimestamp()+")"+newLine);
            }
            return str;
        }

        public String getWalletInBitcoin(){
            String newLine = System.lineSeparator();
            DecimalFormat df = new DecimalFormat("#.########");
            boolean errorOccurred = false;
            String str = null;
            User user = null;
            double amountBitcoin = 0;
            URL randomOrgURL = null;
            double rate = -1;

            try {
                randomOrgURL = new URL(RANDOM_ORG);
            }catch (MalformedURLException urlE){
                //System.out.println("Error generating randomOrgURL: "+urle);
                return "Ops... error calculating wallet value in bitcoin";
            }

            try (InputStream inputStream = randomOrgURL.openStream();
                 InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
                 BufferedReader bufferedReader = new BufferedReader(inputStreamReader))
            {
                try {
                    rate = Double.parseDouble(bufferedReader.readLine());
                } catch (NumberFormatException numE) {
                    //System.out.println("Error reading rate number: "+numE);
                    errorOccurred = true;
                    str = "Ops... error calculating wallet value in bitcoin";
                }
                if(rate <= 0){
                    //System.out.println("Error in rate number: is a non positive number); //DEBUG
                    errorOccurred = true;
                    str =  "Ops... error calculating wallet value in bitcoin";
                }
                if(!errorOccurred) {
                    readLock.lock();
                    user = getUser(getUsernameLogged());
                    readLock.unlock();
                    System.out.println("Random number generated (rate) : "+rate);
                    amountBitcoin = user.getWallet().getWincoin() * rate;
                    str = "Wallet of '" + getUsernameLogged() + "':" + newLine;
                    str = str.concat("\t-Total Amount: \t" + df.format(user.getWallet().getWincoin()) + "wc" + newLine);
                    str = str.concat("\t-Total Amount in Bitcoin:  " + (df.format(amountBitcoin)) + "btc" + newLine);
                }
            }catch (IOException ioE){
                //System.out.println("Error in stream resources : " +ioE);
                return "Ops... error calculating wallet value in bitcoin";
            }
            return str;
        }

        public String readCommandAndCall(String str) throws RemoteException{
            String strToReturn = null;
            String strArr[];
            strArr = str.split(" ");
            String command = strArr[0];
            int idPost;

            //in case of command with two words
            if((strArr.length > 1) && (command.equals("list") || command.equals("show") ||
                    (command.equals("wallet") && strArr[1].equals("btc")))){
                command = command.concat(" "+strArr[1]);
            }
            switch(command){
                case "help": //TO WRITE EVERYTHING INTO STRING strToReturn
                    if(strArr.length != 1){
                        strToReturn = "Error: Invalid number of arguments for 'help' command";
                        break;
                    }
                    strToReturn = getHelpString();
                    break;
                case "register":
                    //never it will happen
                    break;
                case "login":   //login(username, password)
                    if(strArr.length != 3){
                        strToReturn = "Error: Invalid number of arguments for 'login' command";
                        break;
                    }
                    strToReturn = login(strArr[1], strArr[2]);
                    if(strToReturn.equals(strArr[1]+" logged in")){
                        ServerMain.clientsConnected.putIfAbsent(clientSocket, strArr[1]);
                    }
                    break;
                case "logout":  //logout(username)
                    if(strArr.length != 2){
                        strToReturn = "Error: Invalid number of arguments for 'logout' command";
                        break;
                    }
                    strToReturn = logout(strArr[1]);
                    break;
                case "list users": //listUsers()
                    if(strArr.length != 2){
                        strToReturn = "Error: Invalid number of arguments for 'list users' command";
                        break;
                    }
                    strToReturn = listUsers();
                    break;
                case "list followers":  //listFollowers() on client
                    //never it will happen (Made in ClientMain)
                    break;
                case "list following":  //listFollowing()
                    if(strArr.length != 2){
                        strToReturn = "Error: Invalid number of arguments for 'list following' command";
                        break;
                    }
                    strToReturn = listFollowing();
                    break;
                case "follow":  //followUser(idUser)
                    if(strArr.length != 2){
                        strToReturn = "Error: Invalid number of arguments for 'follow' command";
                        break;
                    }
                    strToReturn = followUser(strArr[1]);
                    break;
                case "unfollow":  //unfollowUser(idUser)
                    if(strArr.length != 2){
                        strToReturn = "Error: Invalid number of arguments for 'follow' command";
                        break;
                    }
                    strToReturn = unfollowUser(strArr[1]);
                    break;
                case "blog":  //viewBlog()
                    if(strArr.length != 1){
                        strToReturn = "Error: Invalid number of arguments for 'blog' command";
                        break;
                    }
                    strToReturn = viewBlog();
                    break;
                case "post":  //createPost(title, content)
                    if(strArr.length < 3){
                        strToReturn = "Error: Invalid number of arguments for 'post' command";
                        break;
                    }
                    String titleAndContent[] = makePostTitleAndContent(strArr);
                    if(postArgsAreBadFormatted(titleAndContent)){
                        strToReturn = "Error: Invalid format of the 'post' command\n"+
                                "post: post \"title\" \"content\"";
                        break;
                    }
                    strToReturn = createPost(titleAndContent[0].strip(), titleAndContent[1].strip());
                    break;
                case "show feed":  //showFeed()
                    if(strArr.length != 2){
                        strToReturn = "Error: Invalid number of arguments for 'show feed' command";
                        break;
                    }
                    strToReturn = showFeed();
                    break;
                case "show post":
                    if(strArr.length != 3){
                        strToReturn = "Error: Invalid number of arguments for 'show post' command";
                        break;
                    }
                    try {
                        idPost = Integer.parseInt(strArr[2]);
                    }
                    catch(NumberFormatException e) {
                        strToReturn = "Error: Second parameter must be an integer (idPost >= 0)";
                        break;
                    }
                    strToReturn = showPost(idPost);
                    break;
                case "delete":
                    if(strArr.length != 2){
                        strToReturn = "Error: Invalid number of arguments for 'delete' command";
                        break;
                    }
                    try {
                        idPost = Integer.parseInt(strArr[1]);
                    }
                    catch(NumberFormatException e) {
                        strToReturn = "Error: Parameter must be an integer (idPost >= 0)";
                        break;
                    }
                    strToReturn = deletePost(idPost);
                    break;
                case "rewin":
                    if(strArr.length != 2){
                        strToReturn = "Error: Invalid number of arguments for 'rewin' command";
                        break;
                    }
                    try {
                        idPost = Integer.parseInt(strArr[1]);
                    }
                    catch(NumberFormatException e) {
                        strToReturn = "Error: Parameter must be an integer (idPost >= 0)";
                        break;
                    }
                    strToReturn = rewinPost(idPost);
                    break;
                case "rate":
                    if(strArr.length != 3){
                        strToReturn = "Error: Invalid number of arguments for 'rewin' command";
                        break;
                    }
                    try {
                        idPost = Integer.parseInt(strArr[1]);
                    }
                    catch(NumberFormatException e) {
                        strToReturn = "Error: First parameter must be an integer (idPost >= 0)";
                        break;
                    }
                    int checkVote = 0; //it remains '0' if !strArr[2].equals("+1" or "-1")
                        //it becomes '1' if strArr[2].equals("+1") or '-1' if strArr[2].equals("-1")
                    if((strArr[2].strip()).equals("+1")){
                        checkVote = +1;
                    }else if((strArr[2].strip()).equals("-1")){
                        checkVote = -1;
                    }
                    if(checkVote == 0){
                        strToReturn = "Error: Second parameter must be a vote ('+1' or '-1')";
                    }
                    else{
                        strToReturn = ratePost(idPost, checkVote);
                    }
                    break;
                case "comment":
                    if(strArr.length < 3){
                        strToReturn = "Error: Invalid number of arguments for 'comment' command";
                        break;
                    }
                    try {
                        idPost = Integer.parseInt(strArr[1]);
                    }
                    catch(NumberFormatException e) {
                        strToReturn = "Error: First parameter must be an integer (idPost >= 0)";
                        break;
                    }
                    String comment = makeCommentcomment(strArr);
                    if(commentArgIsBadFormatted(comment)){
                        strToReturn = "Error: Invalid format of the 'comment' command\n"+
                                "comment: comment idPost \"comment\"";
                        break;
                    }
                    strToReturn = addComment(idPost, comment.strip());
                    break;
                case "wallet":
                    if(strArr.length != 1){
                        strToReturn = "Error: Invalid number of arguments for 'wallet' command";
                        break;
                    }
                    strToReturn = getWallet();
                    break;
                case "wallet btc":
                    if(strArr.length != 2){
                        strToReturn = "Error: Invalid number of arguments for 'wallet btc' command";
                        break;
                    }
                    strToReturn = getWalletInBitcoin();
                    break;
                case "exit":
                    //THIS IS DONE IN CLIENT RECEIVING ":q!"
                    //interrupt the socket connection with the specified client!
                    //pay attention to logout the user!
                    break;
                default:
                    strToReturn = "Invalid Command inserted: "+strArr[0];
                    //invalidCommand(command); //method to implement!
            }
            return strToReturn;
        }
    }

    public static String getHelpString(){
        String newLine = System.lineSeparator();
        String helpString =
                 "register: register <username> <password> <tags>"+newLine
                +"    Register a new user with username 'username' and password 'password'"+newLine
                +"    and with the tags specified in 'tags' (max 5 and separated by space)."+newLine
                +"login: login <username> <password>"+newLine
                +"    Login of a registered user to the service."+newLine
                +"logout: logout"+newLine
                +"    Logout a user from the service."+newLine
                +"list users: list users"+newLine
                +"    List of the users who have at least a common tag with the requesting user."+newLine
                +"list followers: list followers"+newLine
                +"    List of the users who the requesting user is followed."+newLine
                +"list following: list following"+newLine
                +"    List of the users who the requesting user is follower of."+newLine
                +"follow: follow <username>"+newLine
                +"    Requesting user follow the user with username 'username'."+newLine
                +"unfollow: unfollow <username>"+newLine
                +"    Requesting user unfollow the user with username 'username'."+newLine
                +"blog: blog"+newLine
                +"    List the posts who the requesting user is author of."+newLine
                +"post: post <\"title\"> <\"content\">"+newLine
                +"    Public a new post with title 'title' (max 20 characters)"+newLine
                +"    and content 'content' (max 500 characters)."+newLine
                +"show feed: show feed"+newLine
                +"    Recover the list of the post in the feed of the requesting user."+newLine
                +"show post: show post <id>"+newLine
                +"    Are shown title, content, likes numbers, dislikes numbers"+newLine
                +"    and comments of the post with id 'id'."+newLine
                +"delete: delete <idPost>"+newLine
                +"    Delete the post with id 'idPost' if and only if "+newLine
                +"the requesting user is the author of the post."+newLine
                +"rewin: rewin <idPost>"+newLine
                +"    Public in the blog of the requesting user the post with id 'idPost'"+newLine
                +" if and only if this post is in requesting user's feed."+newLine
                +"rate: rate <idPost> <vote>"+newLine
                +"    Rate the post with id 'idPost' with the "+newLine
                +"vote 'vote' (like:(+1), dislike:(-1))."+newLine
                +"comment: comment <idPost> <\"comment\">"+newLine
                +"    Comment the post with id 'idPost' with the comment 'comment' "+newLine
                +"    if and only if this post is in the requesting user's feed."+newLine
                +"wallet: wallet"+newLine
                +"    Recover the value of the requesting user's wallet "+newLine
                +"    with the history of its transactions."+newLine
                +"wallet btc: wallet btc"+newLine
                +"    Recover the value of the requesting user's wallet "+newLine
                +"    converted in bitcoin (with the specified exchange value)"+newLine;
        return helpString;
    }


    //Auxiliary method for listFollowing(), followUser(idUser) functions
    //return the user with username 'username' from Users (instance variable)
    public static User getUser(String username){
        User tmpUser = null;
        Iterator<User> iter = Users.iterator();
        while(iter.hasNext()){
            tmpUser = iter.next();
            if((tmpUser.getUsername()).equals(username)){
                break;
            }
        }
        return tmpUser;
    }

    //Auxiliary method for others that handles posts
    //return the post with id 'id' from Posts (instance variable)
    public static Post getPost(int id){
        Post tmpPost = null;
        Iterator<Post> iter = Posts.iterator();
        while(iter.hasNext()){
            tmpPost = iter.next();
            if((tmpPost.getId()) == id){
                break;
            }
        }
        return tmpPost;
    }

    public static void setClientsConnected(Socket clientSocket, String clientUsername) {
        ServerMain.clientsConnected.putIfAbsent(clientSocket, clientUsername);
    }

    public ConcurrentHashMap<Socket, String> getClientsConnected(){
        return ServerMain.clientsConnected;
    }

    //Class to handle TCP socket that terminate unexpectedly
    public static class ConnectionHandler implements Runnable {
        public ConnectionHandler() {
        }

        //We must activate it from the start of main in ServerMain Class.
        //This thread is used to handle a TCP connection lost in server response...
        public void run() {
            Socket clientToCheck = null; //client to check and remove if it isn't connected
            while (true) {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    System.out.println("Exception in sleep in ConnectionHandler thread: " + e);
                }
                Set<Map.Entry<Socket, String>> entrySet = clientsConnected.entrySet();
                Iterator<Map.Entry<Socket, String>> iter = entrySet.iterator();

                while (iter.hasNext()) {
                    Map.Entry<Socket, String> entry = iter.next();
                    clientToCheck = entry.getKey();
                    if (!clientToCheck.isConnected()) {
                        clientsConnected.remove(clientToCheck);
                        loggedUsers.remove(entry.getValue());
                        //System.out.println("REMOVED :" + entry.getValue()); //DEBUG
                    }
                }
                //System.out.println("NOBODY REMOVED");  //DEBUG
            }
        }
    }

    //Class defined to implement RMI callback
    public static class ServerNotifyImpl extends RemoteObject implements ServerNotifyInterface {
        private List<NotifyFollowersInterface> clients; //list of client subscribed to the service

        public ServerNotifyImpl() throws RemoteException{
            super();
            clients = new ArrayList<NotifyFollowersInterface>();
        }

        @Override
        //Subscribe new client 'ClientInterface' to the service
        public synchronized void registerForCallback(NotifyFollowersInterface ClientInterface) throws RemoteException {
            if(!clients.contains(ClientInterface)) {
                clients.add(ClientInterface);
                System.out.println(ANSI_BLUE+"Client registered to the Callback service"+ANSI_RESET);  //Debug
            }
        }

        @Override
        //Unsubscribe client 'ClientInterface' from the service
        public synchronized void unregisterForCallback(NotifyFollowersInterface ClientInterface)
                throws RemoteException
        {
            if((clients.remove((ClientInterface)))){
                System.out.println(ANSI_BLUE+"Client unregistered from Callback service"+ANSI_RESET);  //Debug
            }
            else {
                //System.out.println("Unable to unregister client from service.");  //Debug
            }
        }

        public List<User> restoreFollowers(NotifyFollowersInterface ClientInterface, String username)
                throws RemoteException{
            //System.out.println("START OF RESTORE FOLLOWERS"); //DEBUG
            List<User> followers = new ArrayList<User>();
            Iterator<String> followerIterator = followersMap.get(username).iterator();
            while(followerIterator.hasNext()){
                followers.add(getUser(followerIterator.next()));
            }
            //return new CopyOnWriteArrayList<>(followersMap.get(username));
            return new CopyOnWriteArrayList<>(followers);
        }

        public void update(String userFollowed, List<String> followers, User newFollower)
                throws RemoteException
        {
            doCallbacksUpdate(userFollowed, followers, newFollower);
        }

        public synchronized void doCallbacksUpdate(String userFollowed, List<String> followers, User newFollower)
                throws RemoteException
        {
            //System.out.println("Starting update callbacks.");  //DEBUG
            Iterator<NotifyFollowersInterface> iter = clients.iterator();
            //Iterate on each client subscribed
            while(iter.hasNext()){
                NotifyFollowersInterface client = (NotifyFollowersInterface) iter.next();
                try {
                    List<User> followersUser = makeFollowersUsers(followers);
                    client.notifyFollowersUpdate(userFollowed, followersUser, newFollower);
                }catch (RemoteException e){
                    System.out.println(ANSI_BLUE+"Client left : "+e+ANSI_RESET);
                }
            }
            //System.out.println("Update callbacks complete.");  //DEBUG
        }

        public List<User> makeFollowersUsers(List<String> followers){
            List<User> followersUsers = new ArrayList<User>();
            Iterator<String> followerIterator = followers.iterator();
            while(followerIterator.hasNext()){
                followersUsers.add(getUser(followerIterator.next()));
            }
            return followersUsers;
        }

        public void remove(String userFollowed, List<User> followers, User newFollower)
                throws RemoteException
        {
            doCallbacksRemove(userFollowed, followers, newFollower);
        }

        public synchronized void doCallbacksRemove(String userFollowed, List<User> followers, User oldFollower)
                throws RemoteException
        {
            //System.out.println("Starting remove callbacks.");  //DEBUG
            Iterator<NotifyFollowersInterface> iter = clients.iterator();
            //Iterate on each client subscribed
            while(iter.hasNext()){
                NotifyFollowersInterface client = (NotifyFollowersInterface) iter.next();
                try {
                    client.notifyFollowersRemove(userFollowed, followers, oldFollower);
                }catch (RemoteException e){
                    System.out.println(ANSI_BLUE+"Client left : "+e+ANSI_RESET);
                }
            }
            //System.out.println("Remove callbacks complete.");  //DEBUG
        }
    }

    public static class Storage implements StorageInterface{

        @Override
        public void readJsonStream(InputStream inUsers, InputStream inPosts) throws IOException {
            try{
                System.out.println(ANSI_PURPLE+"Start of readJsonStream"+ANSI_RESET);  //DEBUG
                Users = readUserJsonStream(inUsers);
                prepareFollowersAndFollowingMaps();
                //Perform operation to prepare followersMap and followingMap data structures
                //it must read each user in Users and put into two data structures above
                Posts = readPostJsonStream(inPosts);
            }catch(IOException e){
                System.out.println("Exception occurred in readJsonStream method: "+e);
            }
            System.out.println(ANSI_PURPLE+"End of readJsonStream!"+ANSI_RESET);  //DEBUG
        }

        @Override
        public List<User> readUserJsonStream(InputStream in) throws IOException {
            try(JsonReader reader = new JsonReader(new InputStreamReader(in, "UTF-8")))
            {
                //System.out.println("Inside readUserJsonStream");  //DEBUG
                return readUsersArray(reader);
            }catch(IOException e){
                System.out.println("Exception occurred in readUserJsonStream method: "+e);
            }
            return null;
        }

        @Override
        public List<User> readUsersArray(JsonReader reader) throws IOException {
            List<User> users = new ArrayList<User>();

            try {
                reader.beginArray();
                while (reader.hasNext()) {
                    //System.out.println("In while-loop of readUsersArray"); //DEBUG
                    users.add(readUser(reader));
                }
                reader.endArray();
            }catch(IOException e){
                System.out.println("Exception occurred in readUsersArray method: "+e);
            }
            //System.out.println("End of readUsersArray!"); //DEBUG
            return users;
        }

        @Override
        public User readUser(JsonReader reader) throws IOException {
            String username = null;
            String password = null;
            List<String> tags = new ArrayList<String>(5);
            List<String> followers = new ArrayList<String>();
            List<String> following = new ArrayList<String>();
            Wallet wallet = null;

            try {
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    //System.out.println("At the begin of case -->"); //DEBUG
                    switch(name){
                        case "username":
                            username = reader.nextString();
                            break;
                        case "password":
                            password = reader.nextString();
                            break;
                        case "tags":
                            tags = readTagsArray(reader);
                            break;
                        case "followers":
                            followers = readStringsArray(reader);
                            break;
                        case "following":
                            following = readStringsArray(reader);
                            break;
                        case "wallet":
                            wallet = readWallet(reader);
                            break;
                        default:
                            //System.out.println("Default case...");  //DEBUG
                            reader.skipValue();
                    }
                }
                reader.endObject();
            }catch(IOException e) {
                System.out.println("Exception occurred in readUser method: " + e);
            }
            //System.out.println("End of readUser!"); //DEBUG
            return new User(username, password, tags, followers, following, wallet);
        }

        @Override
        public List<String> readTagsArray(JsonReader reader) throws IOException {
            List<String> tags = new ArrayList<String>(5);

            try {
                reader.beginArray();
                while(reader.hasNext()){
                    if(reader.peek() != JsonToken.NULL) {
                        tags.add(reader.nextString());
                    }
                    //System.out.println("Inside in readTagsArray while-Loop..."); //DEBUG
                }
                reader.endArray();
            }catch(IOException e){
                System.out.println("Exception occurred in readTagsArray method: "+e);
            }
            //System.out.println("End of readTagsArray!"); //DEBUG
            return tags;
        }

        @Override
        public Wallet readWallet(JsonReader reader) throws IOException{
            double wincoin = 0.0;
            List<Transaction> transactions = new ArrayList<Transaction>();

            try {
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    //System.out.println("At the begin of case -->"); //DEBUG
                    switch(name){
                        case "wincoin":
                            wincoin = reader.nextDouble();
                            break;
                        case "transactions":
                            transactions = readTransactionsArray(reader);
                            break;
                        default:
                            //System.out.println("Default case...");  //DEBUG
                            reader.skipValue();
                    }
                }
                reader.endObject();
            }catch(IOException e) {
                System.out.println("Exception occurred in readWallet method: " + e);
            }
            //System.out.println("End of readUser!"); //DEBUG
            return new Wallet(wincoin, transactions);
        }

        @Override
        public List<Transaction> readTransactionsArray(JsonReader reader) throws IOException{
            List<Transaction> transactions = new ArrayList<Transaction>();

            try {
                reader.beginArray();
                while (reader.hasNext()) {
                    //System.out.println("In while-loop of readTransactionsArray"); //DEBUG
                    transactions.add(readTransaction(reader));
                }
                reader.endArray();
            }catch(IOException e){
                System.out.println("Exception occurred in readTransactionsArray method: "+e);
            }
            //System.out.println("End of readTransactionsArray!"); //DEBUG
            return transactions;
        }

        @Override
        public Transaction readTransaction(JsonReader reader) throws IOException{
            double amount = 0;
            String timestamp = null;

            try {
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    //System.out.println("At the begin of case -->"); //DEBUG
                    switch(name){
                        case "amount":
                            amount = reader.nextDouble();
                            break;
                        case "timestamp":
                            timestamp = reader.nextString();
                            break;
                        default:
                            //System.out.println("Default case...");  //DEBUG
                            reader.skipValue();
                    }
                }
                reader.endObject();
            }catch(IOException e) {
                System.out.println("Exception occurred in readTransaction method: " + e);
            }
            //System.out.println("End of readTransaction!"); //DEBUG
            return new Transaction(amount, timestamp);
        }

        @Override
        public List<Post> readPostJsonStream(InputStream in) throws IOException {
            try(JsonReader reader = new JsonReader(new InputStreamReader(in, "UTF-8")))
            {
                //System.out.println("Inside readPostJsonStream");  //DEBUG
                return readPostsArray(reader);
            }catch(IOException e){
                System.out.println("Exception occurred in readPostJsonStream method: "+e);
            }
            return null;
        }

        @Override
        public List<Post> readPostsArray(JsonReader reader) throws IOException {
            List<Post> posts = new ArrayList<Post>();

            try {
                reader.beginArray();
                while (reader.hasNext()) {
                    //System.out.println("In while-loop of readPostsArray"); //DEBUG
                    posts.add(readPost(reader));
                }
                reader.endArray();
            }catch(IOException e){
                System.out.println("Exception occurred in readPostsArray method: "+e);
            }
            //System.out.println("End of readPostsArray!"); //DEBUG
            return posts;
        }

        @Override
        public Post readPost(JsonReader reader) throws IOException {
            int id = 0;
            String title = null;
            String content = null;
            String author = null;
            long creationTime = 0;
            List<String> likes = new ArrayList<String>();
            List<String> dislikes = new ArrayList<String>();
            List<Comment> comments = new ArrayList<Comment>();
            List<String> retweeters = new ArrayList<String>();
            int newVotes = 0;
            List<String> newCommentsBy = new ArrayList<String>();
            List<String> newRaters = new ArrayList<String>();
            int iterations = 0;

            try {
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    //System.out.println("At the begin of case -->"); //DEBUG
                    switch(name){
                        case "id":
                            id = reader.nextInt();
                            break;
                        case "title":
                            title = reader.nextString();
                            break;
                        case "content":
                            content = reader.nextString();
                            break;
                        case "author":
                            author = reader.nextString();
                            break;
                        case "creationTime":
                            creationTime = reader.nextLong();
                            break;
                        case "likes":
                            likes = readStringsArray(reader);
                            break;
                        case "dislikes":
                            dislikes = readStringsArray(reader);
                            break;
                        case "comments":
                            comments = readCommentsArray(reader);
                            break;
                        case "retweeters":
                            retweeters = readStringsArray(reader);
                            break;
                        case "newVotes":
                            newVotes = reader.nextInt();
                            break;
                        case "newCommentsBy":
                            newCommentsBy = readStringsArray(reader);
                            break;
                        case "newRaters":
                            newRaters = readStringsArray(reader);
                            break;
                        case "iterations":
                            iterations = reader.nextInt();
                            break;
                        default:
                            //System.out.println("Default case...");  //DEBUG
                            reader.skipValue();
                    }
                }
                reader.endObject();
            }catch(IOException e) {
                System.out.println("Exception occurred in readUser method: " + e);
            }
            //System.out.println("End of readUser!"); //DEBUG
            return new Post(id, title, content, author, creationTime, likes, dislikes, comments,
                    retweeters, newVotes, newCommentsBy, newRaters, iterations);
        }

        @Override
        public List<Comment> readCommentsArray(JsonReader reader) throws IOException {
            List<Comment> comments = new ArrayList<Comment>();

            try {
                reader.beginArray();
                while (reader.hasNext()) {
                    //System.out.println("In while-loop of readCommentsArray"); //DEBUG
                    comments.add(readComment(reader));
                }
                reader.endArray();
            }catch(IOException e){
                System.out.println("Exception occurred in readCommentsArray method: "+e);
            }
            //System.out.println("End of readCommentsArray!"); //DEBUG
            return comments;
        }

        @Override
        public Comment readComment(JsonReader reader) throws IOException {
            String author = null;
            String text = null;

            try {
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    //System.out.println("At the begin of case -->"); //DEBUG
                    switch(name){
                        case "author":
                            author = reader.nextString();
                            break;
                        case "text":
                            text = reader.nextString();
                            break;
                        default:
                            //System.out.println("Default case...");  //DEBUG
                            reader.skipValue();
                    }
                }
                reader.endObject();
            }catch(IOException e) {
                System.out.println("Exception occurred in readUser method: " + e);
            }
            //System.out.println("End of readUser!"); //DEBUG
            return new Comment(author, text);
        }

        //method to read array of strings
        @Override
        public List<String> readStringsArray(JsonReader reader) throws IOException{
            List<String> stringsArray = new ArrayList<String>();

            try {
                reader.beginArray();
                while(reader.hasNext()){
                    if(reader.peek() != JsonToken.NULL) {
                        stringsArray.add(reader.nextString());
                    }
                    //System.out.println("Inside in readStringsArray while-Loop..."); //DEBUG
                }
                reader.endArray();
            }catch(IOException e){
                System.out.println("Exception occurred in readStringsArray method: "+e);
            }
            //System.out.println("End of readStringsArray!"); //DEBUG
            return stringsArray;
        }

        private void prepareFollowersAndFollowingMaps(){
            User tmpUser = null;
            Iterator<User> userIterator = Users.iterator();
            while(userIterator.hasNext()){
                tmpUser = userIterator.next();
                followersMap.putIfAbsent(tmpUser.getUsername(), tmpUser.getFollowers());
                followingMap.putIfAbsent(tmpUser.getUsername(), tmpUser.getFollowing());
            }
        }



        @Override
        public void writeJsonStream(OutputStream outUsers, OutputStream outPosts) throws IOException {
            try{
                System.out.println(ANSI_PURPLE+"Start of writeJsonStream"+ANSI_RESET);  //Debug
                //Perform operation to prepare Users using followersMap and followingMap data structures
                //it must set each user's followers and following in Users (these are just username (string))
                prepareUsersFollowDataStructure();
                writeUserJsonStream(outUsers, Users);
                //Perform operation to prepare Posts
                writePostJsonStream(outPosts, Posts);
            }catch(IOException e){
                System.out.println("Exception occurred in writeJsonStream method: "+e);
            }
            System.out.println(ANSI_PURPLE+"End of writeJsonStream!"+ANSI_RESET); //DEBUG
        }

        @Override
        public void writeUserJsonStream(OutputStream out, List<User> users) throws IOException {
            try(JsonWriter writer = new JsonWriter(new OutputStreamWriter(out, "UTF-8")))
            {
                writer.setIndent("  ");
                writeUsersArray(writer, users);
            }catch(IOException e){
                System.out.println("Exception occurred in writeUserJsonStream method: "+e);
            }
        }

        @Override
        public void writeUsersArray(JsonWriter writer, List<User> users) throws IOException {
            try {
                writer.beginArray();
                for (User user : users) {
                    writeUser(writer, user);
                }
                writer.endArray();
            }catch(IOException e){
                System.out.println("Exception occurred in writeUsersArray method: "+e);
            }
        }

        @Override
        public void writeUser(JsonWriter writer, User user) throws IOException {
            try {
                writer.beginObject();
                writer.name("username").value(user.getUsername());
                writer.name("password").value(user.getPassword());
                if (user.getTags() != null) {
                    writer.name("tags");
                    writeTagsArray(writer, user.getTags());
                } else {
                    writer.name("tags").nullValue();
                }
                if (user.getFollowers() != null) {
                    writer.name("followers");
                    writeStringsArray(writer, user.getFollowers());
                } else {
                    writer.name("followers").nullValue();
                }
                if (user.getFollowing() != null) {
                    writer.name("following");
                    writeStringsArray(writer, user.getFollowing());
                } else {
                    writer.name("following").nullValue();
                }
                writer.name("wallet");
                writeWallet(writer, user.getWallet());
                writer.endObject();
            }catch(IOException e){
                System.out.println("Exception occurred in writeUser method: "+e);
            }
        }

        @Override
        public void writeTagsArray(JsonWriter writer, List<String> tags) throws IOException {
            try{
                writer.beginArray();
                for(String tag : tags){
                    writer.value(tag);
                }
                writer.endArray();
            }catch(IOException e){
                System.out.println("Exception occurred in writeTagsArray method: "+e);
            }
        }

        @Override
        public void writeWallet(JsonWriter writer, Wallet wallet) throws IOException{
            try {
                writer.beginObject();
                writer.name("wincoin").value(wallet.getWincoin());
                if (wallet.getTransactions() != null) {
                    writer.name("transactions");
                    writeTransactionsArray(writer, wallet.getTransactions());
                } else {
                    writer.name("transactions").nullValue();
                }
                writer.endObject();
            }catch(IOException e){
                System.out.println("Exception occurred in writeWallet method: "+e);
            }
        }

        @Override
        public void writeTransactionsArray(JsonWriter writer, List<Transaction> transactions) throws IOException{
            try {
                writer.beginArray();
                for (Transaction transaction : transactions) {
                    writeTransaction(writer, transaction);
                }
                writer.endArray();
            }catch(IOException e){
                System.out.println("Exception occurred in writeTransactionsArray method: "+e);
            }
        }

        @Override
        public void writeTransaction(JsonWriter writer, Transaction transaction) throws IOException{
            try {
                writer.beginObject();
                writer.name("amount").value(transaction.getAmount());
                writer.name("timestamp").value(transaction.getTimestamp());
                writer.endObject();
            }catch(IOException e){
                System.out.println("Exception occurred in writeTransaction method: "+e);
            }
        }

        @Override
        public void writePostJsonStream(OutputStream out, List<Post> posts) throws IOException {
            try(JsonWriter writer = new JsonWriter(new OutputStreamWriter(out, "UTF-8")))
            {
                writer.setIndent("  ");
                writePostsArray(writer, posts);
            }catch(IOException e){
                System.out.println("Exception occurred in writePostJsonStream method: "+e);
            }
        }

        @Override
        public void writePostsArray(JsonWriter writer, List<Post> posts) throws IOException {
            try {
                writer.beginArray();
                for (Post post : posts) {
                    writePost(writer, post);
                }
                writer.endArray();
            }catch(IOException e){
                System.out.println("Exception occurred in writePostsArray method: "+e);
            }
        }

        @Override
        public void writePost(JsonWriter writer, Post post) throws IOException {
            try {
                writer.beginObject();
                writer.name("id").value(post.getId());
                writer.name("title").value(post.getTitle());
                writer.name("content").value(post.getContent());
                writer.name("author").value(post.getAuthor());
                writer.name("creationTime").value(post.getCreationTime());
                if (post.getLikes() != null) {
                    writer.name("likes");
                    writeStringsArray(writer, post.getLikes());
                } else {
                    writer.name("likes").nullValue();
                }
                if (post.getDislikes() != null) {
                    writer.name("dislikes");
                    writeStringsArray(writer, post.getDislikes());
                } else {
                    writer.name("dislikes").nullValue();
                }
                if (post.getComments() != null) {
                    writer.name("comments");
                    writeCommentsArray(writer, post.getComments());
                } else {
                    writer.name("comments").nullValue();
                }
                if (post.getRetweeters() != null) {
                    writer.name("retweeters");
                    writeStringsArray(writer, post.getRetweeters());
                } else {
                    writer.name("retweeters").nullValue();
                }
                writer.name("newVotes").value(post.getNewVotes());
                if (post.getNewCommentsBy() != null) {
                    writer.name("newCommentsBy");
                    writeStringsArray(writer, post.getNewCommentsBy());
                } else {
                    writer.name("newCommentsBy").nullValue();
                }
                if (post.getNewRaters() != null) {
                    writer.name("newRaters");
                    writeStringsArray(writer, post.getNewRaters());
                } else {
                    writer.name("newRaters").nullValue();
                }
                writer.name("iterations").value(post.getIterations());
                writer.endObject();
            }catch(IOException e){
                System.out.println("Exception occurred in writePost method: "+e);
            }
        }

        @Override
        public void writeCommentsArray(JsonWriter writer, List<Comment> comments) throws IOException {
            try {
                writer.beginArray();
                for (Comment comment : comments) {
                    writeComment(writer, comment);
                }
                writer.endArray();
            }catch(IOException e){
                System.out.println("Exception occurred in writeCommentsArray method: "+e);
            }
        }

        @Override
        public void writeComment(JsonWriter writer, Comment comment) throws IOException {
            try {
                writer.beginObject();
                writer.name("author").value(comment.getAuthor());
                writer.name("text").value(comment.getText());
                writer.endObject();
            }catch(IOException e){
                System.out.println("Exception occurred in writeComment method: "+e);
            }
        }

        //method to write array of strings
        @Override
        public void writeStringsArray(JsonWriter writer, List<String> stringsArray) throws IOException{
            try{
                writer.beginArray();
                for(String string : stringsArray){
                    writer.value(string);
                }
                writer.endArray();
            }catch(IOException e){
                System.out.println("Exception occurred in writeStringsArray method: "+e);
            }
        }

        private void prepareUsersFollowDataStructure(){
            //we have to use setFollowers and setFollowing for each user
            String username = null;
            for(int i=0; i<Users.size(); i++){
                username = Users.get(i).getUsername();
                Users.get(i).setFollowers(followersMap.get(username));
                Users.get(i).setFollowing(followingMap.get(username));
            }
        }

    }

    public static class TimeOutStorage extends Thread{
        @Override
        public void run(){
            while(true) {
                try {
                    Thread.sleep(TIMEOUTSTORAGE);
                } catch (InterruptedException ie) {
                    System.out.println("Exception in TimeoutStorage during sleep : "+ie);
                }
                writeLock.lock();
                storeStorage();
                writeLock.unlock();
            }
        }
    }

    public static class RewardMulticastTask extends Thread{
        private static final String MCAST_MESSAGE = "Rewards have now been calculated!";
        private MulticastSocket mcastSocket = null;
        private InetAddress mcastAddress = null;
        private int mcastPort;
        private int mcastInterval;
        private int authorPercentage;

        public RewardMulticastTask(String address, int portNumber, int interval, int authorPercentage)
            throws UnknownHostException, IOException{
            try{
                this.mcastAddress = InetAddress.getByName(address);
            }catch (UnknownHostException e) {
                System.out.println("Error in RewardMulticast class creating InetAddress: "+e);
                System.exit(-1);
            }
            this.mcastPort = portNumber;
            this.mcastInterval = interval;
            this.authorPercentage = authorPercentage;
            try {
                this.mcastSocket = new MulticastSocket(this.mcastPort);
            }catch (IOException ioE){
                System.out.println("Error in RewardMulticast class creating MulticastSocket: "+ioE);
                System.exit(-1);
            }
        }
        @Override
        public void run(){
            System.out.println(ANSI_GREEN+"RewardMulticast task is now running!"+ANSI_RESET);  //DEBUG
            byte[] bytesMessage = MCAST_MESSAGE.getBytes(StandardCharsets.US_ASCII);
            while(!Thread.currentThread().isInterrupted()){
                try{
                    Thread.sleep(mcastInterval);
                }catch(InterruptedException e){
                    System.out.println("Error during sleep() in RewardMulticastTask : "+e);
                    System.exit(-1);
                }
                rewardsCalculating();
                System.out.println(ANSI_GREEN+MCAST_MESSAGE+ANSI_RESET);  //Debug
                DatagramPacket message = new DatagramPacket(bytesMessage, bytesMessage.length, mcastAddress, mcastPort);
                try{
                    mcastSocket.send(message);
                }catch (IOException ioE){
                    System.out.println("Error in RewardMulticast class sending MulticasSocket message: "+ioE);
                    System.exit(-1);
                }
            }
        }

        public void rewardsCalculating(){
            Post tmpPost = null;
            Map<String, Double> rewardMap = new HashMap<String, Double>();
            writeLock.lock();
            List<Post> posts = new ArrayList<Post>();
            for(int i=0; i<Posts.size(); i++){
                tmpPost = Posts.get(i);
                posts.add(i, tmpPost);
                Posts.set(i, newResettedPost(tmpPost));
            }
            writeLock.unlock();

            double totalGain = 0.0;
            double authorGain = 0.0;
            double curatorsGain = 0.0;
            double singleCuratorGain = 0.0;
            String tmpUser = null;
            List<String> newComments = null;
            List<String> newRaters = null;
            List<String> tmpCurators = null;
            for(int i=0; i<posts.size(); i++){
                tmpPost = posts.get(i);
                newComments = tmpPost.getNewCommentsBy();
                newRaters = tmpPost.getNewRaters();
                tmpCurators = new ArrayList<>();
                for(int j=0; j<newComments.size(); j++){
                    tmpUser = newComments.get(j);
                    if(!(tmpCurators.contains(tmpUser))){
                        tmpCurators.add(tmpUser);
                    }
                }
                for(int j=0; j<newRaters.size(); j++){
                    tmpUser = newRaters.get(j);
                    if(!(tmpCurators.contains(tmpUser))){
                        tmpCurators.add(tmpUser);
                    }
                }
                totalGain = calculateGain(tmpPost); //To divide from author and curators (according percentage)
                //System.out.println("TOTAL_GAIN (post n° "+tmpPost.getId()+") : "+totalGain); //DEBUG
                authorGain = (double) (totalGain * authorPercentage)/100;
                Double lastAuthorAmount = rewardMap.putIfAbsent(tmpPost.getAuthor(), Double.valueOf(authorGain));
                if(lastAuthorAmount != null){
                    lastAuthorAmount = rewardMap.get(tmpPost.getAuthor());
                    rewardMap.put(tmpPost.getAuthor(), Double.valueOf(lastAuthorAmount.doubleValue() + authorGain));
                }
                curatorsGain = (double) (totalGain * (100 - authorPercentage)) / 100;
                if(tmpCurators.size() != 0) {
                    singleCuratorGain = (double) curatorsGain / tmpCurators.size();
                }
                for(int j=0; j<tmpCurators.size(); j++){
                    Double lastAmount = rewardMap.putIfAbsent(tmpCurators.get(j), Double.valueOf(singleCuratorGain));
                    if(lastAmount != null){
                        lastAmount = rewardMap.get(tmpCurators.get(j));
                        rewardMap.put(tmpCurators.get(j), Double.valueOf(lastAmount.doubleValue() + singleCuratorGain));
                    }
                }
            }
            writeLock.lock();
            for(int i=0; i<Users.size(); i++){
                User user = Users.get(i);
                Double amount = rewardMap.get(user.getUsername());
                if(amount != null) {
                    user.addTransactionToWallet(amount.doubleValue());
                }else{
                    user.addTransactionToWallet(0.0);
                }
                Users.set(i, user);
            }
            writeLock.unlock();
        }

        public double calculateGain(Post post){
            double newPeopleLikes = (double) post.getNewVotes();
            List<String> commentersList = post.getNewCommentsBy();
            Map<String, Integer> CpMap = new HashMap<String, Integer>();
            double gain = 0;
            double numeratorAddend1 = 0.0;
            double numeratorAddend2 = 0.0;
            double denominator = post.getIterations()+1;
            //perform first numerator addend
            double logArg = Math.max(newPeopleLikes, 0) + 1.0;
            numeratorAddend1 = Math.log(logArg);
            //System.out.println("NUMERATOR_ADDEND1 : "+numeratorAddend1);  //DEBUG
            //prepare values and perform second numerator addend
            List<String> newCommentersList = new ArrayList<>();
            String tmpCommenter = null;
            Integer tmpCount = null;
            for(int i=0; i<commentersList.size(); i++){
                tmpCommenter = commentersList.get(i);
                tmpCount = CpMap.putIfAbsent(tmpCommenter, Integer.valueOf(1));
                if(tmpCount != null){
                    CpMap.put(tmpCommenter, Integer.valueOf(tmpCount.intValue()+1));
                }else{
                    newCommentersList.add(tmpCommenter);
                }
            }
            double sumLogArg = 0;
            for(int i=0; i<newCommentersList.size(); i++){
                sumLogArg = sumLogArg + (2 / (1 + Math.exp((-1)*((CpMap.get(newCommentersList.get(i))).intValue() - 1))));
            }
            numeratorAddend2 = (double) Math.log(sumLogArg + 1);
            //System.out.println("NUMERATOR_ADDEND2 : "+numeratorAddend2);  //DEBUG
            gain = (double) (numeratorAddend1 + numeratorAddend2) / denominator;
            return gain;
        }

        public Post newResettedPost(Post post){
            return new Post(post.getId(), post.getTitle(), post.getContent(), post.getAuthor(),
                            post.getCreationTime(), post.getLikes(), post.getDislikes(),
                            post.getComments(), post.getRetweeters(), 0, new ArrayList<String>(),
                            new ArrayList<String>(), post.getIterations()+1);
        }
    }

    public static class CloseServer extends Thread{
        @Override
        public void run(){
            Scanner userInput = new Scanner(System.in);
            try {
                while(true) {
                    String line = userInput.nextLine();
                    if(line.equals(":q!")){
                        System.exit(0);
                    }
                }
            }catch (IllegalStateException | NoSuchElementException e){
                System.out.println("Error in CloseServer read() method : "+e);
            }
        }
    }

    public static class ShutDown extends Thread{
        @Override
        public void run(){
            writeLock.lock();
            storeStorage();
            writeLock.unlock();
            //unbind and deallocate rmi resources (rarely necessary):
            try {
                registry.unbind(REGHOST);
                UnicastRemoteObject.unexportObject(serverMainObj, true);
                return;
            } catch (NotBoundException | RemoteException e) {
                //System.err.println("registry unbind exception : "+e); //ignored
            }
            try {
                registryCallBack.unbind(REGHOST);
                UnicastRemoteObject.unexportObject(serverNotify, true);
                return;
            } catch (NotBoundException | RemoteException e) {
                //System.err.println("registryCallBack unbind exception : "+e); //ignored
            }
            System.out.println(ANSI_RED+"End of ShutDown thread... Bye bye!"+ANSI_RESET);
        }
    }
}

