package android.content.pm;

import android.os.Parcelable;

import java.util.List;

abstract class BaseParceledListSlice<T> implements Parcelable {
    
    public BaseParceledListSlice(List<T> list) {
        throw new RuntimeException("STUB");
    }
    
    public List<T> getList() {
        throw new RuntimeException("STUB");
    }
    
}
