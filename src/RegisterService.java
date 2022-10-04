import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/*
Interface implemented by ServerMain.
This Remote Interface provides a remote method
that permits a client to register to winsome
 */
public interface RegisterService extends Remote {
    public String register(String username, String password, List<String> tags)
            throws RemoteException;
}
