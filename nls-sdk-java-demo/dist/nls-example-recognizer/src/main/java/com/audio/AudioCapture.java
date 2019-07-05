package com.audio;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import javax.sound.sampled.LineUnavailableException;

public class AudioCapture {
    public static boolean stopped = false;
    public static PipedInputStream input = new PipedInputStream();
    public static void Capture() throws Exception {
        final PipedOutputStream out = new PipedOutputStream(input);
        // input.connect(out);
        float sampleRate = 16000;
        int sampleSizeInBits = 16;
        int channels = 1;
        boolean signed = true;
        boolean bigEndian = false;
        final AudioFormat format = new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        final TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        Runnable runner = new Runnable() {
            int bufferSize = (int) format.getSampleRate() * format.getFrameSize();
            byte buffer[] = new byte[bufferSize];

            public void run() {
                try {
                    line.open(format);
                    line.start();
                    System.out.println("------------- audio capture started -------------");

                    TimerTask task = new TimerTask() {
                        public void run() {
                            stopped = true;
                            System.out.println("------------- audio capture finished -------------");
                        }
                    };
                    Timer timer = new Timer("Timer");
                    long delay = 10000L; //millisecond
                    timer.schedule(task, delay);

                    while(!stopped){
                        int count = line.read(buffer, 0, buffer.length);
                        if (count > 0) {
                            // System.out.println(Arrays.toString(buffer));
                            out.write(buffer, 0, count);
                        }
                    }
                    out.close();
                    timer.cancel();
                } catch (Exception e) {
                    System.err.println("Exception in capture: " + e);
                    System.exit(-1);
                } 
            }
        };
        Thread captureThread = new Thread(runner);
        captureThread.start();

        // byte audio[] = out.toByteArray();
    }

}