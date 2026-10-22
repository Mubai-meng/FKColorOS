package android.content.pm;

import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.List;

@SuppressLint("NewApi")
public class ParceledListSlice<T extends Parcelable> extends BaseParceledListSlice<T> {
    
    static final Parcelable.ClassLoaderCreator<Parcelable> CREATOR = new Parcelable.ClassLoaderCreator<>() {
        @Override
        public ParceledListSlice<Parcelable> createFromParcel(Parcel source, ClassLoader loader) {
            throw new RuntimeException("STUB");
        }
        
        @Override
        public ParceledListSlice<Parcelable> createFromParcel(Parcel source) {
            throw new RuntimeException("STUB");
        }
        
        @Override
        public ParceledListSlice<Parcelable>[] newArray(int size) {
            throw new RuntimeException("STUB");
        }
    };
    
    public ParceledListSlice(List<T> list) {
        super(list);
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
    
    }
}
