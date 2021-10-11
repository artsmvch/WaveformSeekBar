# WaveformSeekBar
SeekBar variant that shows waveform data

![WaveformSeekBar](https://github.com/alexei-frolo/WaveformSeekBar/blob/master/media/src/waveform_seek_bar.png)

## Getting started

### Setting up the dependency

First of all, include the library in your project.

1. Add it in your root build.gradle at the end of repositories:
```groovy
allprojects {
	repositories {
		...
		maven { url 'https://jitpack.io' }
	}
}
```

2. Add the dependency:
```groovy
implementation 'com.github.alexei-frolo:WaveformSeekBar:1.1'
```

### WaveformSeekBar example

This section explains how to use **WaveformSeekBar**.

First, add the view to xml layout:

```xml
...

<com.frolo.waveformseekbar.WaveformSeekBar
    android:id="@+id/waveform_seek_bar"
    android:layout_width="match_parent"
    android:layout_gravity="center"
    android:layout_height="100dp"
    android:layout_margin="16dp"/>

...

```

Then you can set up waveform data and listen to progress changes as shown below:

```java
...
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_waveformseekbar_example);

    final WaveformSeekBar seekBar = findViewById(R.id.waveform_seek_bar);
    seekBar.setOnSeekBarChangeListener(new WaveformSeekBar.Callback() {
        @Override
        public void onProgressChanged(WaveformSeekBar seekBar, float percent, boolean fromUser) {

        }

        @Override
        public void onStartTrackingTouch(WaveformSeekBar seekBar) {

        }

        @Override
        public void onStopTrackingTouch(WaveformSeekBar seekBar) {
            Toast.makeText(
                    WaveformSeekBarExampleActivity.this,
                    "Tracked: percent=" + seekBar.getProgressPercent(),
                    Toast.LENGTH_SHORT).show();
        }
    });
    seekBar.setWaveform(createWaveform(), true);

    findViewById(R.id.btn_regenerate).setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            seekBar.setWaveform(createWaveform(), true);
        }
    });
}

private int[] createWaveform() {
    //final Random random = new Random(System.currentTimeMillis());

    final int length = 50;
    final int[] values = new int[length];
    int maxValue = 0;

    for (int i = 0; i < length; i++) {
        final int newValue = 5 + random.nextInt(50);
        if (newValue > maxValue) {
            maxValue = newValue;
        }
        values[i] = newValue;
    }
    return values;
}
...
```
