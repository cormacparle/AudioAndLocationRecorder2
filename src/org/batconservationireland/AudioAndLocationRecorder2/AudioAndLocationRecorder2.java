/*
 * Copyright (C) 2009 Rhodri Karim (rk395)
 * Copyright (C) 2012 Daniel Thomas (drt24)
 * Copyright (C) 2013 Cormac Parle (cormacparle)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.batconservationireland.AudioAndLocationRecorder2;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.batconservationireland.AudioAndLocationRecorder2.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * An activity that allows the user to record full-quality audio and save to a WAV file
 * plus simultaneously record location and save to a .csv file
 * 
 * @author Rhodri Karim
 * @author Cormac Parle
 * 
 */
public class AudioAndLocationRecorder2 extends Activity {

  private static final int WAV_HEADER_LENGTH = 44;
  private static final SimpleDateFormat filenameDateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");
  private static final SimpleDateFormat displayDateFormat = new SimpleDateFormat("HH:mm:ss");
  private static final int NOTICE_RECORD = 0;

  private Button actionButton;
  private EditText editText;
  private String filename;
  private ProgressBar saving;
  private View startedRecording;
  private TextView startedRecordingTime;

  private AlertDialog dialog;

  private File outFile;

  private boolean isListening;
  
  private LocationManager locationManager = null;
  private LocationListener locationListener = null;
  private PrintWriter locationFileWriter = null;
  private long recordingStartTime  = 0;
  
  private CheckBox checkBox;

  /**
   * The sample rate at which we'll record, and save, the WAV file.
   */
  public int sampleRate = 44100;
  private NotificationManager notificationManager;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);

    // set up GUI references
    actionButton = (Button) findViewById(R.id.actionButton);
    editText = (EditText) findViewById(R.id.editText);
    saving = (ProgressBar) findViewById(R.id.saving);
    startedRecording = findViewById(R.id.startedRecording);
    startedRecordingTime = (TextView)findViewById(R.id.startedRecordingTime);
    checkBox = (CheckBox)findViewById(R.id.monitorSignal);

    // get a generic dialog ready for alerts
    dialog = new AlertDialog.Builder(this).create();
    dialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK", new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialogInterface, int which) {
        dialogInterface.dismiss();
      }
    });
    notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

    // add GUI functionality
    saving.setVisibility(View.GONE);
    editText.setSingleLine(true);

    
    actionButton.setOnClickListener(new OnClickListener() {

      @Override
      public void onClick(View v) {

        // if we're already recording... start saving
        if (isListening) {
          endRecording();
        } else {
          beginRecording();
        }
      }

    });
    
    locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
  }

  /**
   * End the recording, saving and finalising the file
   */
  private void endRecording() {
    isListening = false;
    Thread thread = new Thread() {
      @Override
      public void run() {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            startedRecording.setVisibility(View.GONE);
            actionButton.setEnabled(false);
            actionButton.setText("Saving...");
            saving.setVisibility(View.VISIBLE);
          }
        });

        if (outFile != null) {
          appendHeader(outFile);

          Intent scanWav = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
          scanWav.setData(Uri.fromFile(outFile));
          sendBroadcast(scanWav);

          outFile = null;
          notificationManager.cancel(NOTICE_RECORD);
        }

        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            actionButton.setEnabled(true);
            editText.setEnabled(true);
            checkBox.setEnabled(true);
            checkBox.setChecked(true);
            actionButton.setText("Start recording");
            saving.setVisibility(View.GONE);
          }
        });
      }
    };
    thread.start();
    
    //Stop the audio recording too
    locationManager.removeUpdates(locationListener);
	if (locationFileWriter instanceof PrintWriter) {
		locationFileWriter.close();
		locationFileWriter = null;
	}
  }

  /**
   * Begin the recording after verifying that we can, if we can't then tell the user and return
   */
  private void beginRecording() {

    // check that there's somewhere to record to
    String state = Environment.getExternalStorageState();
    Log.d("FS State", state);
    if (state.equals(Environment.MEDIA_SHARED)) {
      showDialog("Unmount USB storage", "Please unmount USB storage before starting to record.");
      return;
    } else if (state.equals(Environment.MEDIA_REMOVED)) {
      showDialog("Insert SD Card", "Please insert an SD card. You need something to record onto.");
      return;
    }

    // check that the user's supplied a file name
    Pattern pattern = Pattern.compile("^[A-Za-z][0-9]{2}$");
    Matcher matcher = pattern.matcher(editText.getText().toString());
    if (matcher.find()) {
    	filename = editText.getText().toString();
    	Date date = new Date();
        filename += '-' + filenameDateFormat.format(date);
    } else {
      editText.setText("");
      showDialog("Enter square", "Must be one letter and two numbers e.g. N23");
      return;
    }
    
    
    // ask if file should be overwritten
    File audioFile = new File(Environment.getExternalStorageDirectory() + "/" + filename + ".wav");
    if (audioFile.exists()) {
      AlertDialog.Builder builder = new AlertDialog.Builder(AudioAndLocationRecorder2.this);
      builder.setTitle("File already exists").setMessage(
          "Do you want to overwrite the existing " + "file with that name?").setCancelable(false)
          .setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int id) {
              dialogInterface.dismiss();
              startRecording();
            }
          }).setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int id) {
              dialogInterface.cancel();
            }
          });
      AlertDialog alert = builder.create();
      alert.show();
    } else { // otherwise, start recording
      startRecording();
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    isListening = false;
  }

  public void startRecording() {
    isListening = true;
    checkBox.setEnabled(false);
    editText.setEnabled(false);
    actionButton.setText("Stop recording");
    
    Thread s = new Thread(new SpaceCheck());
    s.start();
    
    //LOCATION RECORDING
    String sdDirectory = Environment.getExternalStorageDirectory().toString();
    File locationFile = new File(sdDirectory + "/" + filename + ".csv");
	if (locationFile.exists()) {
		locationFile.delete();
	}
		
	try {
		locationFile.createNewFile();
		locationFileWriter = new PrintWriter(locationFile);
	} catch (Exception e) {
		e.printStackTrace();
		showDialog("Error creating file", "The location file '" + filename + ".csv' "
                    + "couldn't be created. Please try again.");
        locationFileWriter = null;
        actionButton.performClick();
    }
	recordingStartTime = 0;
	locationFileWriter.println("Seconds since recording started,Latitude,Longitude,Accuracy (m),Speed (m/s),Altitude (m)");
	locationFileWriter.flush();
	  
	// Define a listener that responds to location updates
	locationListener = new LocationListener() {
	    public void onLocationChanged(Location location) {
	    	if (recordingStartTime == 0) {
	    		recordingStartTime = System.currentTimeMillis();
	    	}
			long msSinceStart = System.currentTimeMillis() - recordingStartTime;
	    	locationFileWriter.println(Double.toString((double)msSinceStart / 1000) + "," +
    			location.getLatitude() + "," +
				location.getLongitude() + "," +
				location.getAccuracy() + "," +
				location.getSpeed() + "," +
  				location.getAltitude());
		    	locationFileWriter.flush();
		}
		public void onStatusChanged(String provider, int status, Bundle extras) {}
		public void onProviderEnabled(String provider) {}
		public void onProviderDisabled(String provider) {}
	};
	
	// Register the listener with the Location Manager to receive location updates
	locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10, 10, locationListener);
	locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10, 10, locationListener);
    
	//AUDIO RECORDING    
    Thread t = new Thread(new Capture());
    t.start();
	
    startedRecordingTime.setText(displayDateFormat.format(new Date()));
    startedRecording.setVisibility(View.VISIBLE);
    setNotification();
  }

  private void setNotification() {
    CharSequence notificationTitle = getText(R.string.notification_title);
    Notification notification = new Notification(R.drawable.icon, notificationTitle, System.currentTimeMillis());
    int flags = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
    notification.setLatestEventInfo(getApplicationContext(), notificationTitle,
        getText(R.string.notifification_content),
        PendingIntent.getActivity(this,0,new Intent(this, AudioAndLocationRecorder2.class),0));
    notification.flags |= flags;
    notificationManager.notify(NOTICE_RECORD, notification);
  }

  /**
   * Monitors the available SD card space while recording.
   * 
   * @author Rhodri Karim
   * 
   */
  private class SpaceCheck implements Runnable {
    @Override
    public void run() {
      String sdDirectory = Environment.getExternalStorageDirectory().toString();
      StatFs stats = new StatFs(sdDirectory);
      while (isListening) {
        stats.restat(sdDirectory);
        final long freeBytes = (long) stats.getAvailableBlocks() * (long) stats.getBlockSize();
        if (freeBytes < 5242880) { // less than 5MB remaining
          runOnUiThread(new Runnable() {
            @Override
            public void run() {
              showDialog("Low on disk space", "There isn't enough space " + "left on your SD card (" + freeBytes
                  + "b) , but what you've " + "recorded up to now has been saved.");
              actionButton.performClick();
            }
          });
          return;
        }

        try {
          Thread.sleep(3000);
        } catch (InterruptedException e) {
        }
      }
    }
  }

  /**
   * Capture raw audio data from the hardware and saves it to a buffer in the enclosing class.
   * 
   * @author Rhodri Karim
   * 
   */
  private class Capture implements Runnable {

    private final int channelConfig = AudioFormat.CHANNEL_CONFIGURATION_MONO;
    private final int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;
    
    //The variables below are used to check the quality of the input signal
    //
    //The signal should be quiet for 320ms while the detector is sampling, 
    //then loud for 3200ms when it's playing back the time-expanded sample

    //The threshold for determining whether the signal is quiet or loud
    //is set as a fraction of the average taken over the first X buffers
    //
    //Then there's a grace period of Y buffers where we don't check the signal
    //
    //Then we monitor the signal for Z buffers, then we assume it'll be ok
    //after that
    
    private byte averageFor = 30;
    private byte ignoreFor = 1;
    private int monitorFor = 150;
    
     
    //The audio buffer is 4096 samples long, which is around 90ms, so if the
    //signal is quiet for more than approx 5 audio buffers then there's probably 
    //a problem with the lead
    private float quietSignalThreshold = 0;
    private byte quietSignalCountThreshold = 5;
    
    //there should be 3200ms / audio-buffer-length loud samples
    //before the next quiet sample (approx 34, use min-max range)
    private byte countSinceLastQuietSignalMin = 30;
    private byte countSinceLastQuietSignalMax = 40;
    
    //If the quietSignalThreshold is below this number then really the signal
    //is just too quiet to be distinguishable from noise
    private float thisQuietIsTooQuiet = 200;

    // the actual output format is big-endian, signed

    @Override
    public void run() {
      // We're important...
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
      

      //Initialise values for signal checking variables
      int quietSignalCount = 0;
      int countSinceLastQuietSignal = 0;
      boolean checkSignal = false;
      int bufferCount = 0;
      int sumAmplitudeMax = 0;
      
      // Allocate Recorder and Start Recording...
      int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding);
      Log.d("Capture", "Min buffer size: " + minBufferSize);
      if (AudioRecord.ERROR_BAD_VALUE == minBufferSize || AudioRecord.ERROR == minBufferSize){
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            showDialog("Error recording audio", "Your audio hardware doesn't support the sampling rate you have specified." +
              "Try a lower sampling rate, if that doesn't work your audio hardware might be broken.");
            actionButton.performClick();
          }
        });
        return;
      }
      int bufferSize = 2 * minBufferSize;
      AudioRecord recordInstance =
          new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioEncoding,
              bufferSize);
      if (recordInstance.getState() != AudioRecord.STATE_INITIALIZED) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            showDialog("Error recording audio", "Unable to access the audio recording hardware - is your mic working?");
            actionButton.performClick();
          }
        });
        return;
      }

      byte[] tempBuffer = new byte[bufferSize];

      String sdDirectory = Environment.getExternalStorageDirectory().toString();
      outFile = new File(sdDirectory + "/" + filename + ".wav");
      if (outFile.exists())
        outFile.delete();

      FileOutputStream outStream = null;
      try {
        outFile.createNewFile();
        outStream = new FileOutputStream(outFile);
        outStream.write(createHeader(0));// Write a dummy header for a file of length 0 to get updated later
      } catch (Exception e) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            showDialog("Error creating file", "The audio file '" + filename + ".wav' "
                + "couldn't be created. Please try again.");
            outFile = null;
            actionButton.performClick();
          }
        });
        return;
      }

      recordInstance.startRecording();

      try {
        while (isListening) {
          recordInstance.read(tempBuffer, 0, bufferSize);
          outStream.write(tempBuffer);
          
          int amplitudeMax = 0;
          for (int i=0; i < tempBuffer.length/2; i++) {
        	  int amplitude = Math.abs((int)getShort(tempBuffer[i*2], tempBuffer[i*2+1]));
        	  if (amplitude > amplitudeMax) {
        		  amplitudeMax = amplitude;
              }
          }
          //Check for problems with signal
          if (checkBox.isChecked()) {
        	  if (bufferCount == 0) {
        		  runOnUiThread(new Runnable() {
        	    	  @Override
        	          public void run() {
		        		  actionButton.setText("Checking signal ...");
		        		  saving.setVisibility(View.VISIBLE);
        	    	  }
        		  });
        	  } else if (bufferCount < (int)averageFor && bufferCount != 0) { 		
        		  sumAmplitudeMax += amplitudeMax; 
        	  } else if (bufferCount == (int)averageFor + 1) {				//+1 cos we skipped the first buffer
        		  
        		  quietSignalThreshold = (sumAmplitudeMax / averageFor) * 9/10;
        		  Log.i("Capture", "qst " + quietSignalThreshold);
    			  
        		  if (quietSignalThreshold < thisQuietIsTooQuiet) {
        			  //Average level is just too low
        			  throw new SignalTooQuietException();
        		  }
        	  } else if (bufferCount == (int)averageFor + (int)ignoreFor) {
        		  checkSignal = true;
        	  } else if (bufferCount == (int)averageFor + (int)ignoreFor + monitorFor) {
        		  checkSignal = false;
        		  runOnUiThread(new Runnable() {
        	    	  @Override
        	          public void run() {
        	    		  actionButton.setText("Stop recording");
        	    	      saving.setVisibility(View.GONE);    		  
        	    	  }
        	      });
        	  }
        	  bufferCount++;
          }
          if (amplitudeMax < quietSignalThreshold) {
        	  quietSignalCount++;
        	  if (quietSignalCount == 1 && checkSignal) {
        		  //if this is the first of a new set of quiet signals, check if the gap between
        		  //this set of quiet signals and the previous one was long enough
        		  if (countSinceLastQuietSignal < countSinceLastQuietSignalMin) {
        			  throw new SignalWrongPatternException();
    	          } 
        	  }
        	  countSinceLastQuietSignal = 0;
          } else {
        	  quietSignalCount = 0;
        	  countSinceLastQuietSignal++;
          }
          if (checkSignal) {
	          if (quietSignalCount > quietSignalCountThreshold) {
        		  //the signal has been quiet for too long
				  throw new SignalTooQuietException();
	          } else if (countSinceLastQuietSignal > countSinceLastQuietSignalMax) {
	        	  //the signal has been loud for too long
	        	  throw new SignalWrongPatternException();
	          } 
          }
          Log.i("Capture", bufferCount + " Amplitude max " + amplitudeMax + " qsc " + quietSignalCount + " cslqs " + countSinceLastQuietSignal);
        }
        
      } catch (final IOException e) {
        runOnUiThread(new Runnable() {

          @Override
          public void run() {
            showDialog("IO Exception", "An exception occured when writing to disk or reading from the microphone\n"
                    + e.getLocalizedMessage()
                    + "\nWhat you have recorded so far should be saved to disk.");
            actionButton.performClick();
          }

        });
      } catch (OutOfMemoryError om) {
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            showDialog("Out of memory", "The system has been " + "too strong for too long - but what you "
                + "recorded up to now has been saved.");
            System.gc();
            actionButton.performClick();
          }
        });
      } catch (SignalTooQuietException stq) {
    	  runOnUiThread(new Runnable() {
		      @Override
		      public void run() {
		    	showDialog("Bad audio signal", new StringBuilder()
		    		.append("Audio signal too quiet - please check your lead and your detector sensitivity and volume.")
		    		.append("\nStopped recording at ")
		    		.append(displayDateFormat.format(new Date()))
		    		.append(".\nWhat you have recorded so far has been saved to disk.")
		    		.toString());
		        actionButton.performClick();
		      }
		  });
      } catch (SignalWrongPatternException swp) {
    	  runOnUiThread(new Runnable() {
		      @Override
		      public void run() {
		    	showDialog("Bad audio signal", new StringBuilder()
		    		.append("Please check your lead and the 'time' and 'div' settings on your detector.")
		    		.append("\nStopped recording at ")
		    		.append(displayDateFormat.format(new Date()))
		    		.append(".\nWhat you have recorded so far has been saved to disk.")
		    		.toString());
		        actionButton.performClick();
		      }
		  });
      } 

      // we're done recording
      Log.d("Capture", "Stopping recording");
      recordInstance.stop();
      try {
    	outStream.close();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }
  
  private class SignalTooQuietException extends Exception {}
  private class SignalWrongPatternException extends Exception {}

  private void showDialog(String title, String message){
    dialog.setTitle(title);
    dialog.setMessage(message);
    dialog.show();
  }

  /**
   * Appends a WAV header to a file containing raw audio data. Uses different strategies depending
   * on amount of free disk space.
   * 
   * @param file The file containing 16-bit little-endian PCM data.
   */
  public void appendHeader(File file) {

    int bytesLength = (int) file.length();
    byte[] header = createHeader(bytesLength - WAV_HEADER_LENGTH);

    try {
      RandomAccessFile ramFile = new RandomAccessFile(file, "rw");
      ramFile.seek(0);
      ramFile.write(header);
      ramFile.close();
    } catch (FileNotFoundException e) {
      Log.e("Hertz", "Tried to append header to invalid file: " + e.getLocalizedMessage());
      return;
    } catch (IOException e) {
      Log.e("Hertz", "IO Error during header append: " + e.getLocalizedMessage());
      return;
    }

  }

  /**
   * Creates a valid WAV header for the given bytes, using the class-wide sample rate
   * 
   * @param bytes The sound data to be appraised
   * @return The header, ready to be written to a file
   */
  public byte[] createHeader(int bytesLength) {

    int totalLength = bytesLength + 4 + 24 + 8;
    byte[] lengthData = intToBytes(totalLength);
    byte[] samplesLength = intToBytes(bytesLength);
    byte[] sampleRateBytes = intToBytes(this.sampleRate);
    byte[] bytesPerSecond = intToBytes(this.sampleRate * 2);

    ByteArrayOutputStream out = new ByteArrayOutputStream();

    try {
      out.write(new byte[] {'R', 'I', 'F', 'F'});
      out.write(lengthData);
      out.write(new byte[] {'W', 'A', 'V', 'E'});

      out.write(new byte[] {'f', 'm', 't', ' '});
      out.write(new byte[] {0x10, 0x00, 0x00, 0x00}); // 16 bit chunks
      out.write(new byte[] {0x01, 0x00, 0x01, 0x00}); // mono
      out.write(sampleRateBytes); // sampling rate
      out.write(bytesPerSecond); // bytes per second
      out.write(new byte[] {0x02, 0x00, 0x10, 0x00}); // 2 bytes per sample

      out.write(new byte[] {'d', 'a', 't', 'a'});
      out.write(samplesLength);
    } catch (IOException e) {
      Log.e("Create WAV", e.getMessage());
    }

    return out.toByteArray();
  }

  /**
   * Turns an integer into its little-endian four-byte representation
   * 
   * @param in The integer to be converted
   * @return The bytes representing this integer
   */
  public static byte[] intToBytes(int in) {
    byte[] bytes = new byte[4];
    for (int i = 0; i < 4; i++) {
      bytes[i] = (byte) ((in >>> i * 8) & 0xFF);
    }
    return bytes;
  }
  
	/* 
	 * 
	 * Converts a byte[2] to a short, in LITTLE_ENDIAN format
	 * 
	 */
	private short getShort(byte argB1, byte argB2)
	{
		return (short)(argB1 | (argB2 << 8));
	}
}