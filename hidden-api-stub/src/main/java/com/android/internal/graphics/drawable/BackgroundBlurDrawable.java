package com.android.internal.graphics.drawable;

import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;

/** @noinspection ALL*/
public abstract class BackgroundBlurDrawable extends Drawable {
    
    public void setAlpha(int alpha) {
        throw new RuntimeException("STUB");
    }
    
    public void setBlurColor(float r, float g, float b, float a) {
    
    }
    
    public void setBlurRadius(int blurRadius) {
        throw new RuntimeException("STUB");
    }
    
    public void setBounds(int left, int top, int right, int bottom) {
        throw new RuntimeException("STUB");
    }
    
    public void setColor(int color) {
        throw new RuntimeException("STUB");
    }
    
    public void setColorFilter(ColorFilter colorFilter) {
        throw new RuntimeException("STUB");
    }
    
    public void setCornerRadius(float cornerRadius) {
        throw new RuntimeException("STUB");
    }
    
    public void setCornerRadius(float cornerRadiusTL, float cornerRadiusTR, float cornerRadiusBL, float cornerRadiusBR) {
        throw new RuntimeException("STUB");
    }
    
    public boolean setVisible(boolean visible, boolean restart) {
        throw new RuntimeException("STUB");
    }
    
}
