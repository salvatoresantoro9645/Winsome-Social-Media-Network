/**
 * This structure construct each comment as a couple made of <author,text> fields.
 */
public class Comment {
    final private String author; //Primary key for the author: username
    final private String text; //unbound lenght

    public Comment(String author, String text){
        this.author = author;
        this.text = text;
    }

    public String getAuthor(){
        return this.author;
    }

    public String getText(){
        return this.text;
    }
}
