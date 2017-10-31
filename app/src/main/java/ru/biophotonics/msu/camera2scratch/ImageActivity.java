package ru.biophotonics.msu.camera2scratch;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class ImageActivity extends AppCompatActivity {

    private ImageView mImageView;
    private Uri mUri;
    private TextView mTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image);

        mImageView = (ImageView) findViewById(R.id.image_view);
        Intent intent = getIntent();

        mTextView = (TextView) findViewById(R.id.text_view);

        String path = intent.getStringExtra("ImagePath");
        mTextView.setText("current path is: "+path);

        try{
            mImageView.setImageURI(Uri.fromFile(new File(path)));
        } catch(Exception e){
            e.printStackTrace();
            Toast.makeText(getApplicationContext(),
                    "Something bad happend", Toast.LENGTH_SHORT).show();
        }
    }
}
