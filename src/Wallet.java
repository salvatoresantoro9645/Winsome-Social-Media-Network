import java.util.ArrayList;
import java.util.List;

public class Wallet implements java.io.Serializable {

    private static final long serialVersionUID = 1L;
    private double wincoin;
    private List<Transaction> transactions;

    public Wallet() {
        this.wincoin = 0;
        this.transactions = new ArrayList<Transaction>();
        this.transactions.add(new Transaction(this.wincoin));
        //We have a first transaction with value of amount '0'
    }

    //Used only by Storage for JSON write
    public Wallet(double amount, List<Transaction> transactions) {
        this.wincoin = amount;
        this.transactions = new ArrayList<Transaction>(transactions);
    }

    public double getWincoin() {
        return this.wincoin;
    }

    public List<Transaction> getTransactions() {
        return this.transactions;
    }

    public int getTransactionsNumber() {
        return this.transactions.size();
    }

    public void addTransaction(double amount) {
        this.transactions.add(new Transaction(amount));
        this.wincoin = this.wincoin + amount;
    }

}
