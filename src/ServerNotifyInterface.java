import java.rmi.*;
import java.rmi.RemoteException;
import java.util.List;

/*
Remote interface provided by the Server.
Remote interface with remote methods used by the clients
to register their interest in receiving followers update notifications
 */
public interface ServerNotifyInterface extends Remote {

    public void registerForCallback(NotifyFollowersInterface ClientInterface)
            throws RemoteException;

    public void unregisterForCallback(NotifyFollowersInterface ClientInterface)
            throws RemoteException;

    public List<User> restoreFollowers(NotifyFollowersInterface ClientInterface, String username)
        throws RemoteException;
}
