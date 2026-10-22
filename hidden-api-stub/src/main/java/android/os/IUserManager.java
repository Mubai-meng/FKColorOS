package android.os;

import android.content.pm.UserInfo;

import java.util.List;

public interface IUserManager extends IInterface {
    
    abstract class Stub extends Binder implements IUserManager {
        
        public static IUserManager asInterface(IBinder iBinder) {
            throw new RuntimeException("STUB");
        }
        
    }
    
    boolean isUserUnlocked(int userId) throws RemoteException;
    
    List<UserInfo> getUsers(boolean excludeDying) throws RemoteException;
    
    UserInfo getUserInfo(int userHandle) throws RemoteException;
    
}
