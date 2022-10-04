import java.util.*;


public class Post {
    final private int id;
    final private String title; //max 20 lenght //HOW TO ADD CONSTRAINT
    final private String content; //max 500 lenght
    final private String author; //IT SHOULD BE USER??? USING JSON WITH READ AUTHOR AND USER FOR VALUE...
    final private long creationTime; //time in Millis of creation
    private List<String> likes; //one per user
    private List<String> dislikes; //one per user
    private List<Comment> comments; //more per user
    private List<String> retweeters; //user who have retweeted this post

    //instances variables used for wallet
    private int newVotes;  //Weighted sum of number of vote received recently (likes: +1, dislikes: -1)
    private List<String> newCommentsBy;  //username who comment recently (method to calculate numbers of recently comment per user)
    private List<String> newRaters; //user who rates recently this post
    private int iterations; //number of iterations on this post by wincom calculator

    public Post(int id, String title, String content, String author){
        this.id = id;
        this.title = title;
        this.content = content;
        this.author = author;
        this.creationTime = System.currentTimeMillis();
        this.likes = new ArrayList<>();
        this.dislikes = new ArrayList<>();
        this.comments = new ArrayList<>();
        this.retweeters = new ArrayList<>();
        this.newVotes = 0;
        this.newCommentsBy = new ArrayList<>();
        this.newRaters = new ArrayList<>();
        this.iterations = 0;
    }

    //Used by Storage for JSON write
    public Post(int id, String title, String content, String author, long creationTime,
                List<String> likes, List<String> dislikes, List<Comment> comments, List<String> retweeters,
                int newVotes, List<String> newCommentsBy, List<String> newRaters, int iterations){
        this.id = id;
        this.title = title;
        this.content = content;
        this.author = author;
        this.creationTime = creationTime;
        this.likes = new ArrayList<>(likes);
        this.dislikes = new ArrayList<>(dislikes);
        this.comments = new ArrayList<>(comments);
        this.retweeters = new ArrayList<>(retweeters);
        this.newVotes = newVotes;
        this.newCommentsBy = new ArrayList<>(newCommentsBy);
        this.newRaters = new ArrayList<>(newRaters);
        this.iterations = iterations;
    }

    public int getId(){
        return this.id;
    }

    public String getTitle() {
        return this.title;
    }

    public String getContent() {
        return this.content;
    }

    public String getAuthor(){
        return this.author;
    }

    public long getCreationTime(){
        return this.creationTime;
    }

    public List<String> getLikes(){
        return this.likes;
    }

    //true if user never voted (likes) the post, false otherwise
    public boolean addLike(String user){
        boolean added1 = false;
        if(!likes.contains(user) && !dislikes.contains(user)){
            added1 = this.likes.add(user);
        }
        if(added1) {
            this.addNewVotes(+1);
            if(!newRaters.contains(user)) {
                this.newRaters.add(user);
            }
        }
        return added1;
    }

    public int getLikesNum(){
        return this.likes.size();
    }

    public List<String> getDislikes(){
        return this.dislikes;
    }

    //true if user never voted (dislikes) the post, false otherwise
    public boolean addDislike(String user){
        boolean added1 = false;
        if(!likes.contains(user) && !dislikes.contains(user)){
            added1 = this.dislikes.add(user);
        }
        if(added1) {
            this.addNewVotes(-1);
            if(!newRaters.contains(user)) {
                this.newRaters.add(user);
            }
        }
        return added1;
    }

    public int getDislikesNum(){
        return this.dislikes.size();
    }

    public List<Comment> getComments(){
        return this.comments;
    }

    public void addComment(String author, String text){
        Comment comment = new Comment(author, text);
        this.comments.add(comment);
        this.newCommentsBy.add(author);
    }

    public List<String> getRetweeters() {
        return this.retweeters;
    }

    public void setRetweeters(List<String> retweeters){
        this.retweeters = retweeters;
    }

    public void addRetweeter(String user){
        this.retweeters.add(user);
    }

    public int getNewVotes() {
        return this.newVotes;
    }

    //rate could be '+1' or '-1';
    public void addNewVotes(int rate){
        this.newVotes = this.newVotes + rate;
    }

    public List<String> getNewCommentsBy() {
        return this.newCommentsBy;
    }

    public List<String> getNewRaters() {
        return this.newRaters;
    }

    public int getIterations() {
        return this.iterations;
    }

}
