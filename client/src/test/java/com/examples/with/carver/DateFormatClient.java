package com.examples.with.carver;

import java.text.DateFormat;
import java.util.Date;

public class DateFormatClient {
    public String formatWithFactory(Date date) {
        return DateFormat.getDateInstance().format(date);
    }
}
