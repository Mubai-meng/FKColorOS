package android.content.pm;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

public class UserInfo implements Parcelable {
    
    public int id;
    public String name;
    
    protected UserInfo(Parcel in) {
        id = in.readInt();
        name = in.readString();
    }
    
    public static final Creator<UserInfo> CREATOR = new Creator<>() {
        @Override
        public UserInfo createFromParcel(Parcel in) {
            return new UserInfo(in);
        }
        
        @Override
        public UserInfo[] newArray(int size) {
            return new UserInfo[size];
        }
    };
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
    
    }
}
