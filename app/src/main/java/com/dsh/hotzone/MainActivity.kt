package com.dsh.hotzone

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        Player.ensure(this)

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            Player.tap()
            status.text = getString(R.string.status_played)
        }
    }
}
