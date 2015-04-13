package com.evolve.multimedia.EvoLMPL;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ShortBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.AudioDevice;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.evolve.pixeldefender.GameManager;
import com.xuggle.xuggler.Global;
import com.xuggle.xuggler.IAudioSamples;
import com.xuggle.xuggler.ICodec;
import com.xuggle.xuggler.IContainer;
import com.xuggle.xuggler.IPacket;
import com.xuggle.xuggler.IPixelFormat;
import com.xuggle.xuggler.IStream;
import com.xuggle.xuggler.IStreamCoder;
import com.xuggle.xuggler.IVideoPicture;
import com.xuggle.xuggler.IVideoResampler;
import com.xuggle.xuggler.Utils;
import com.xuggle.xuggler.video.ConverterFactory;
import com.xuggle.xuggler.video.IConverter;

/**
 * A VideoTextureProvider wraps a video on the internal file system.
 * It provides methods to play, pause and stop the associated video
 * and provides a Texture object that contains the current video
 * frame
 * @author ajs
 * 
 * April 8th 2015 - Audio support added by @author Sean Brophy
 * courtesy of Evolve Interactive Inc.
 * 3732 Knight St.
 * Vancouver, BC
 * V5N 3L6
 * www.evolveinteractive.ca
 */
public class VideoPlayer {
	
	public enum PlayState {
		PLAYING,
		PAUSED,
		STOPPED
	}

	private String videoPath;
	private FileHandle fileHandle;
	private InputStream inputStream;
	
	//the audio device to write output
	private AudioDevice gdxAudioDevice = null;
	
	private Texture texture;
	
	private PlayState playState = PlayState.STOPPED;
	private long playTimeMilliseconds = 0;
	private IContainer container;
	
	private long firstTimestampMilliseconds = Global.NO_PTS;
	
	// The tollerance used when waiting for the playhead to catch up
	private long sleepTolleranceMilliseconds = 50;
	
	private static SourceDataLine mLine;
	
	int videoStreamId = -1;
	IStreamCoder videoCoder = null;
	
	//the audio stream from the codec
	int audioStreamId = -1;
	IStreamCoder audioCoder = null;
	
	IVideoResampler resampler = null;
	IPacket packet = IPacket.make();
	
	long sleepTimeoutMilliseconds = 0;
	
	ExecutorService writeOutPool;
	private IAudioSamples samples;
	private IConverter converter;
	private IVideoPicture picture;
	
	VideoScreen screen;
	
	/**
	 * ctor
	 * @param _videoPath An internal LibGDX path to a video file
	 * @param videoScreenInstance the instance of the libGDX video screen
	 */
	public VideoPlayer(String _videoPath, VideoScreen videoScreenInstance) {
		this.screen = videoScreenInstance;
		videoPath = _videoPath;
		writeOutPool = Executors.newSingleThreadExecutor();
		// Let's make sure that we can actually convert video pixel formats.
		if (!IVideoResampler.isSupported(IVideoResampler.Feature.FEATURE_COLORSPACECONVERSION)) {
			throw new RuntimeException("VideoTextureProvider requires the GPL version of Xuggler (with IVideoResampler support)");
		}
		
		// Get a handle to the file and open it for reading
		fileHandle = Gdx.files.internal(videoPath);
		
		if(!fileHandle.exists()) {
			throw new IllegalArgumentException("Video file does not exist: " + videoPath);
		}
		
		inputStream = fileHandle.read();
		
		// Initialize the texture to a black color until the video is ready
		texture = new Texture(Gdx.files.internal("mov/black.png"));
		setDefaultTexture();
	}

	/**
	 * Plays the video stream, or resumes it if it was paused
	 */
	public void play() {
		
		if(container == null) {
			// Create a Xuggler container object
			container = IContainer.make();
		}
		
		if(!container.isOpened()) {
			// Open up the container
			if (container.open(inputStream, null) < 0) {
				throw new RuntimeException("Could not open video file: " + videoPath);
			}
	
			// Query how many streams the call to open found
			int numStreams = container.getNumStreams();
	
			// Iterate through the streams to find the first video stream
			for (int i = 0; i < numStreams; i++) {
				// Find the stream object
				IStream stream = container.getStream(i);
				
				// Get the pre-configured decoder that can decode this stream;
				IStreamCoder coder = stream.getStreamCoder();
				
				if (coder.getCodecType() == ICodec.Type.CODEC_TYPE_VIDEO) {
					videoStreamId = i;
					videoCoder = coder;
					break;
				}
			}
			// now find the audio stream
			for (int i = 0; i < numStreams; i++) {
				// Find the stream object
				IStream stream = container.getStream(i);
				
				// Get the pre-configured decoder that can decode this stream;
				IStreamCoder coder = stream.getStreamCoder();
				
				if (coder.getCodecType() == ICodec.Type.CODEC_TYPE_AUDIO) {
					audioStreamId = i;
					audioCoder = coder;
					break;
				}
			}
			
			if (videoStreamId == -1) {
				throw new RuntimeException("Could not find video stream in container: " + videoPath);
			}
			
			if (audioStreamId == -1) {
				throw new RuntimeException("Could not find audio stream in container: " + videoPath);
			}
			
			if(audioCoder.open() < 0)
			{
				throw new RuntimeException("Could not open audio decoder for container: " + videoPath);
			}
			else
			{
				//now prep the audio stream
				openJavaSound(audioCoder);
			}
			this.samples = IAudioSamples.make(4096, audioCoder.getChannels());
			/* Now we have found the video stream in this file. Let's open up our
			 * decoder so it can do work
			 */
			if (videoCoder.open() < 0) {
				throw new RuntimeException("Could not open video decoder for container: " + videoPath);
			}
			
			this.picture = IVideoPicture.make(
					videoCoder.getPixelType(),
					videoCoder.getWidth(),
					videoCoder.getHeight()
				);
			
			/* Query the first timestamp in the stream
			 * Timestamps are in microseconds - convert to milli
			 */
			firstTimestampMilliseconds = container.getStartTime() / 1000;
		}
		samples.setTimeStamp(picture.getTimeStamp());
		playState = PlayState.PLAYING;
	}
	
	/**
	 * Pauses the video stream, allowing it to be resumed later
	 * with play()
	 */
	public void pause() {
		playState = PlayState.PAUSED;
	}
	
	/**
	 * Stops the video stream, resetting the play head to the
	 * beginning of the stream
	 */
	public void stop() {
		if(container != null)
		{
			container.close();
		}
		container = null;
		
		try {
			inputStream.close();
		} catch (IOException e) {}
		inputStream = fileHandle.read();
		
		playTimeMilliseconds = 0;
		
		// Initialize the texture to a black color until it is next played
		
		setDefaultTexture();
		
		playState = PlayState.STOPPED;
	}
	
	private void setDefaultTexture()
	{
		texture = new Texture(new FileHandle("mov/black.png"));
		if(screen.sprite == null) {
			// Initialize the sprite
			screen.sprite = new Sprite(texture);
			
			screen.sprite.setSize(1.0f, 0.5f * screen.sprite.getHeight() / screen.sprite.getWidth());
			screen.sprite.setOrigin(screen.sprite.getWidth()/2, screen.sprite.getHeight()/2);
			screen.sprite.setPosition(-screen.sprite.getWidth()/2, -screen.sprite.getHeight()/2);
		}
		else
		{
			screen.sprite.setTexture(texture);
		}
	}
	
	public PlayState getState() {
		return playState;
	}
	
	/**
	 * Updates the video play head
	 * @param dtSeconds The elapsed time since the last call to update(),
	 * 	in seconds
	 * @return True if the video texture has changed, false otherwise
	 */
	public void update(float dtSeconds) {
		if(playState != PlayState.PLAYING) return;
		
		long syncTolerance = 90;
		
		long dtMilliseconds = (long)(dtSeconds * 1000);
		playTimeMilliseconds += dtMilliseconds;
		
		sleepTimeoutMilliseconds = (long) Math.max(0, sleepTimeoutMilliseconds - dtMilliseconds);
		if(sleepTimeoutMilliseconds > 0) {
			// The playhead is still ahead of the current frame - do nothing
			return;
		}
		
		

		while(container.readNextPacket(packet) >= 0) {
			
			/*If the difference between the audio and the video is above the threshold for human perception (about 75ms), seek
			 * the video ahead. TODO experiment with seeking the audio backwards. Or seeking both to a middle point.*/
			if(samples.getTimeStamp()/1000 - picture.getTimeStamp()/1000 > syncTolerance)
			{
				System.out.println("Audio: " + samples.getTimeStamp() + " " + " Video: " + picture.getTimeStamp() + " Difference(ms): " 
																		+ (samples.getTimeStamp()/1000 - picture.getTimeStamp()/1000));
				container.seekKeyFrame(videoStreamId, samples.getTimeStamp() - syncTolerance * 1000, samples.getTimeStamp(), 
													samples.getTimeStamp() + 1000*syncTolerance, IContainer.SEEK_FLAG_ANY);
				samples.setTimeStamp(picture.getTimeStamp());
			}
			if(packet.getStreamIndex() == videoStreamId) 
			{
				// Attempt to read the entire packet
				int offset = 0;
				while(offset < packet.getSize()) {
					// Decode the video, checking for any errors
					int bytesDecoded = videoCoder.decodeVideo(picture, packet, offset);
					
					if (bytesDecoded < 0) {
						throw new RuntimeException("Got error decoding video");
					}
					offset += bytesDecoded;

					/* Some decoders will consume data in a packet, but will not
					 * be able to construct a full video picture yet. Therefore
					 * you should always check if you got a complete picture
					 * from the decoder
					 */
					if (picture.isComplete()) {
						// We've read the entire packet
						IVideoPicture newPic = picture;
						
						// Timestamps are stored in microseconds - convert to milli
						long absoluteFrameTimestampMilliseconds = picture.getTimeStamp() / 1000;
						long relativeFrameTimestampMilliseconds = (absoluteFrameTimestampMilliseconds - firstTimestampMilliseconds);
						long frameTimeDelta = relativeFrameTimestampMilliseconds - playTimeMilliseconds;
						if(frameTimeDelta > 0) {
							// The video is ahead of the playhead, don't read any more frames until it catches up
							sleepTimeoutMilliseconds = frameTimeDelta + sleepTolleranceMilliseconds;
							return;
						}

						//converts the image to the desired format, added some optimization here, since the
						//old code was using an old inefficient method for doing this
						if(this.converter == null)
							this.converter = ConverterFactory.createConverter(ConverterFactory.XUGGLER_BGR_24, newPic);
						BufferedImage javaImage = converter.toImage(newPic);
						
						// Update the current texture (now done via callback)
						updateTexture(javaImage);
						
						continue;
					}
				}
			}
			else if(packet.getStreamIndex() == this.audioStreamId)
			{
				int offset = 0;
				while(offset < packet.getSize())
		        {
					 int bytesDecoded = audioCoder.decodeAudio(samples, packet, offset);
					 if (bytesDecoded < 0)
						 throw new RuntimeException("got error decoding audio in: " + this.videoPath);
			          offset += bytesDecoded;
		        }
				if(samples.isComplete())
				{
					//once we have a complete sample we send the byte array to a thread that handles writing to the sound buffer
		        	this.writeOutPool.execute(new WriteOutSoundBytes(samples.getData().getByteArray(0, samples.getSize())));
				}
				
			}
			
		}
		stop();
		return;
	}
	
	/**
	 * Updates the internal texture with new video data
	 * @param img The new video frame data
	 */
	private void updateTexture(BufferedImage img) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		
		try {
			ImageIO.write(img, "bmp", baos);
			byte[] bytes = baos.toByteArray();
			Pixmap pix = new Pixmap(bytes, 0, bytes.length);
			texture = new Texture(pix);
			screen.sprite.setTexture(texture);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}

	/**
	 * Gets the video texture
	 * @return The video texture, containing the current video frame
	 */
	public Texture getTexture() {
		return texture;
	}
	
	private static void openJavaSound(IStreamCoder aAudioCoder)
	{
	    AudioFormat audioFormat = new AudioFormat(aAudioCoder.getSampleRate(),
	        (int)IAudioSamples.findSampleBitDepth(aAudioCoder.getSampleFormat()),
	        aAudioCoder.getChannels(),
	        true, /* xuggler defaults to signed 16 bit samples */
	        false);
	    DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
	    try
	    {
	      mLine = (SourceDataLine) AudioSystem.getLine(info);
	      /**
	       * if that succeeded, try opening the line.
	       */
	      mLine.open(audioFormat);
	      /**
	       * And if that succeed, start the line.
	       */
	      mLine.start();
	    }
	    catch (LineUnavailableException e)
	    {
	      throw new RuntimeException("could not open audio line");
	    }   
	}

	
	/**
	 * Permanently disposes of all objects
	 */
	public void dispose() {
		closeJavaSound();
		if(inputStream != null) {
			try {
				inputStream.close();
			} catch(Exception e) {}
			inputStream = null;
		}

		if(texture != null) {
			texture.dispose();
			texture = null;
		}
		
		if (videoCoder != null) {
			videoCoder.close();
			videoCoder = null;
		}
		
		if (container != null) {
			container.close();
			container = null;
		}
	}
	
	private static void closeJavaSound()
	{
	    if (mLine != null)
	    {
	      /*
	       * Wait for the line to finish playing
	       */
	      mLine.drain();
	      /*
	       * Close the line.
	       */
	      mLine.close();
	      mLine=null;
	    }
	}
	
	private class WriteOutSoundBytes implements Runnable
	{
		byte[] rawByte;
		public WriteOutSoundBytes(byte[] rawBytes)
		{
			rawByte = rawBytes;
		}
		@Override
		public void run() 
		{
			mLine.write(rawByte, 0, rawByte.length);
		}
		
		
	}

}
