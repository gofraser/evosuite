package com.examples.with.carver;

import java.text.DateFormat;
import java.text.FieldPosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class FinalMethodClient {
    public void calendarSetDate() {
        Calendar.getInstance().set(2000, 1, 2);
    }

    public void calendarSetMinutes() {
        Calendar.getInstance().set(2000, 1, 2, 3, 4);
    }

    public void calendarSetSeconds() {
        Calendar.getInstance().set(2000, 1, 2, 3, 4, 5);
    }

    public void calendarClear() {
        Calendar.getInstance().clear();
    }

    public void calendarClearField() {
        Calendar.getInstance().clear(Calendar.YEAR);
    }

    public boolean calendarIsSet() {
        return Calendar.getInstance().isSet(Calendar.YEAR);
    }

    public void calendarSetTime(Date date) {
        Calendar.getInstance().setTime(date);
    }

    public Object calendarToInstant() {
        return Calendar.getInstance().toInstant();
    }

    public String simpleDateFormatFormatDate(Date date) {
        return new SimpleDateFormat("yyyy").format(date);
    }

    public String simpleDateFormatFormatObject(Date date) {
        return new SimpleDateFormat("yyyy").format((Object) date);
    }

    public String simpleDateFormatFormatBuffer(Date date) {
        return new SimpleDateFormat("yyyy").format(date, new StringBuffer(), new FieldPosition(0)).toString();
    }

    public String dateFormatFormatObject(Date date) {
        return DateFormat.getDateInstance().format((Object) date);
    }
}
