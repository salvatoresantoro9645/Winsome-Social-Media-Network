import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class User implements java.io.Serializable{
    private static final long serialVersionUID=1L;
    private String username;
    private String password; //saved using sha_256 (No empty field)
    final List<String> tags; //maxSize 5
    private List<String> followers;
    private List<String> following;
    private Wallet wallet;

    public User(String username, String password, List<String> tags){
        this.username = username;
        this.password = sha256Encode(password);
        this.tags = tags;
        this.followers = new ArrayList<>();
        this.following = new ArrayList<>();
        this.wallet = new Wallet();
    }

    //Used only by Storage for JSON write
    public User(String username, String password, List<String> tags, List<String> followers, List<String> following, Wallet wallet){
        this.username = username;
        this.password = password; //passed argument is encoded yet
        this.tags = tags;
        this.followers = new ArrayList<>(followers);
        this.following = new ArrayList<>(following);
        this.wallet = wallet;
    }


    public String getUsername(){
        return this.username;
    }

    //return the value of password (hexadecimal string) encoded with sha-256
    private String sha256Encode(String password){
        Hash hash = new Hash();
        byte[] encodedBytesPassword = null;
        try {
            encodedBytesPassword = hash.sha256(password);
        }catch (NoSuchAlgorithmException e){
            System.out.println("Error encoding password : "+e);
            System.exit(-1);
        }
        return hash.bytesToHex(encodedBytesPassword);
    }

    //return password value (encoded with sha-256)
    public String getPassword(){
        return this.password;
    }

    //COMPARING MUST BE DONE DECODING THIS.PASSWORD (IT WAS PREVIOUS ENCODING WITH SHA-256)
    public boolean comparePassword(String password){
        return this.password.equals(sha256Encode(password));
    }

    public List<String> getTags(){
        return this.tags;
    }

    public List<String> getFollowers(){
        return this.followers;
    }

    public void setFollowers(List<String> followers){
        this.followers = new ArrayList<>(followers);
    }

    public int getFollowersNumber(){
        return this.followers.size();
    }

    public List<String> getFollowing(){
        return this.following;
    }

    public void setFollowing(List<String> following){
        this.following = new ArrayList<>(following);
    }

    public int getFollowingNumbers(){
        return this.following.size();
    }

    public Wallet getWallet() {
        return wallet;
    }

    public void addTransactionToWallet(double amount){
        this.wallet.addTransaction(amount);
    }

    /**
     *@override
     */
    public boolean equals(User user){
        return (this.username).equals(user.getUsername());
    }

    /**
     * @override
     */
    public String toString(){
        String newLine = System.lineSeparator();
        List<String> tags = getTags();
        String toPrint = new String();
        toPrint = toPrint.concat("Username : "+getUsername()+newLine);
        toPrint = toPrint.concat("password : "+getPassword()+newLine);
        toPrint = toPrint.concat("tags : <");
        int i=0;
        for(i=0; i<tags.size()-1; i++){
            toPrint = toPrint.concat(tags.get(i)+", ");
        }
        toPrint = toPrint.concat(tags.get(i)+">"+newLine);
        return toPrint;
    }
}









