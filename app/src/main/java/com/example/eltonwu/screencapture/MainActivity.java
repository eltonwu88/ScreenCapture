package com.example.eltonwu.screencapture;

import android.content.ComponentName;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        MediaProjectionManager mMediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mMediaProjectionManager.createScreenCaptureIntent(),233);
        Log.i("TEST","act create");
        startService(new Intent(this,ScreenCaptureService.class));
        startService(new Intent(this,ADBService.class));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == 233){
            if(resultCode == RESULT_OK){
                Intent intent = new Intent(data);
                intent.setComponent(new ComponentName(this,ScreenCaptureService.class));
                intent.putExtra("cmd","start");
                startService(intent);
                Log.i("TEST","start service");
            }else{
                Toast.makeText(this,"Permission Denied",Toast.LENGTH_SHORT);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i("TEST","act destroy");
    }
}
