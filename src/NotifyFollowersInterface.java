import java.rmi.*;
import java.util.List;

/*
Remote interface provided by the Client.
Remote interface with remote methods used by the Server
to notify a followers update event to the client
 */
public interface NotifyFollowersInterface extends Remote{

    //Notify the client when a new user started following it
    public void notifyFollowersUpdate(String userFollowed, List<User> users, User newFollower)
        throws RemoteException;

    //Notify the client when a user stopped following it
    public void notifyFollowersRemove(String userFollowed, List<User> users, User oldFollower)
            throws RemoteException;
}
