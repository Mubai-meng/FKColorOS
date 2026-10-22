package com.oplus.util;

/**
 * @noinspection ALL
 */
public class OplusChineseDateAndSolarDate {
    
    static {
        System.loadLibrary("ChineseDateAndSolarDate");
    }
    
    public static int[] ChineseDateToSunDate(int iChineseYear, int iChineseMonth, int iChineseDay) {
        throw new RuntimeException("STUB");
    }
    
    public static int[] SunDateToChineseDate(int iSunDateYear, int iSunDateMonth, int iSunDateDay) {
        throw new RuntimeException("STUB");
    }
    
    public static int GetChLeapMonth(int iChineseYear) {
        throw new RuntimeException("STUB");
    }
    
    public static int GetChMonthDays(int iChineseYear, int iChineseMonth) {
        throw new RuntimeException("STUB");
    }
    
    public static int GetSolarMonthDays(int iSolarYear, int iSolarMonth) {
        throw new RuntimeException("STUB");
    }
    
}
