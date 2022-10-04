import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/*
Interface with all method needed to maintain the state of system.
The server must implements all these method to store and restore users and posts information
 */
public interface StorageInterface {

    //Used at the start of the server
    //Method to read from userStorage.txt and postStorage.txt
    //and to write to structures Users, followingMap, followersMap, Posts
    public void readJsonStream(InputStream in1, InputStream in2) throws IOException;

    //Called by readJsonStream to read from UsersStorage.txt
    //and write to Users, followingMap and followersMap
    public List<User> readUserJsonStream(InputStream in) throws IOException;

    //Read array of users from UsersStorage.txt
    public List<User> readUsersArray(JsonReader reader) throws IOException;

    //Read of the user object with switch-case to switch fields of the object
    public User readUser(JsonReader reader) throws IOException;

    //Used into readUser()
    public List<String> readTagsArray(JsonReader reader) throws IOException;

    //Used into readUser()
    public Wallet readWallet(JsonReader reader) throws IOException;

    //Used into readUser()
    public List<Transaction> readTransactionsArray(JsonReader reader) throws IOException;

    //Used into readUser()
    public Transaction readTransaction(JsonReader reader) throws IOException;

    //Called by readJsonStream to read from PostsStorage.txt and write to Posts
    public List<Post> readPostJsonStream(InputStream in) throws IOException;

    //Read array of posts from PostsStorage.txt
    public List<Post> readPostsArray(JsonReader reader) throws IOException;

    //Read of the post object with switch-case to switch fields of the object
    public Post readPost(JsonReader reader) throws IOException;

    //Used into readPosts()
    public List<Comment> readCommentsArray(JsonReader reader) throws IOException;

    //Used into readPosts()
    public Comment readComment(JsonReader reader) throws IOException;

    //method to read array of strings
    public List<String> readStringsArray(JsonReader reader) throws IOException;


    //Used periodically and at the end of the server
    //Method to read from structures Users, followingMap, followersMap, Posts
    //and to write to userStorage.txt and postStorage.txt
    public void writeJsonStream(OutputStream out1, OutputStream out2) throws IOException;

    //Called by writeJsonStream to read from Users, followingMap and followersMap
    //and write to UsersStorage.txt
    public void writeUserJsonStream(OutputStream out, List<User> users) throws IOException;

    //Write array of users to UsersStorage.txt
    public void writeUsersArray(JsonWriter writer, List<User> users) throws IOException;

    //Write of the user object with switch-case to switch fields of the object
    public void writeUser(JsonWriter writer, User user) throws IOException;

    //Used into writeUser()
    public void writeTagsArray(JsonWriter writer, List<String> tags) throws IOException;

    //Used into writeUser()
    public void writeWallet(JsonWriter writer, Wallet wallet) throws IOException;

    //Used into writeUser()
    public void writeTransactionsArray(JsonWriter writer, List<Transaction> transactions) throws IOException;

    //Used into writeUser()
    public void writeTransaction(JsonWriter writer, Transaction transaction) throws IOException;

    //Called by readJsonStream to read from Posts and write to PostsStorage.txt
    public void writePostJsonStream(OutputStream out, List<Post> posts) throws IOException;

    //Write array of posts to PostsStorage.txt
    public void writePostsArray(JsonWriter writer, List<Post> posts) throws IOException;

    //Write of the post object with switch-case to switch fields of the object
    public void writePost(JsonWriter writer, Post post) throws IOException;

    //Used into writePosts()
    public void writeCommentsArray(JsonWriter writer, List<Comment> comments) throws IOException;

    //Used into writePosts()
    public void writeComment(JsonWriter writer, Comment comment) throws IOException;

    //method to write array of strings
    public void writeStringsArray(JsonWriter writer, List<String> stringsArray) throws IOException;

}
