package com.example.android.heartrate;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class view_history extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_history);

        LinearLayout linearLayout = findViewById(R.id.linearlayout);
        
        SharedPreferences prefs = getSharedPreferences("HeartRateHistory", MODE_PRIVATE);
        String historyString = prefs.getString("history", "");

        if (historyString.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("No history available yet. Try measuring your heart rate!");
            tv.setTextSize(18f);
            linearLayout.addView(tv);
        } else {
            String[] historyItems = historyString.split("\n");
            for (String item : historyItems) {
                TextView tv = new TextView(this);
                tv.setText(item);
                tv.setTextSize(18f);
                tv.setPadding(0, 16, 0, 16);
                linearLayout.addView(tv);
            }
        }
    }
}
