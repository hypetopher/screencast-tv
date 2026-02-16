package com.screencast.tv

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

class PlaybackActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        if (savedInstanceState == null) {
            val fragment = PlaybackFragment().apply {
                arguments = Bundle().apply {
                    putString("video_url", intent.getStringExtra("video_url"))
                    putString("video_title", intent.getStringExtra("video_title"))
                    putDouble("start_position", intent.getDoubleExtra("start_position", 0.0))
                }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.playback_fragment_container, fragment)
                .commit()
        }
    }
}
