package gsn.hpgl;

//import java.io.*;
import java.awt.Graphics2D;
import java.util.ArrayList;
import processing.core.PApplet;
import processing.core.PGraphics;
import processing.core.PMatrix2D;
import processing.core.PVector;

/*
 *	A PGraphics derivative that converts Processing commands into HPGL to send
 *	out on a serial port plotter
 *
 *	Information about HPGL: http://www.isoplotec.co.jp/HPGL/eHPGL.htm
 *	http://paulbourke.net/dataformats/hpgl/
 *
 *	Programming manual for the HP7550A, which is the one I have currently can be
 *	downloaded from: http://www.hpmuseum.net/exhibit.php?hwdoc=75
 *
 *	More information about plotter 7550A
 *  One unit = 40/mm = 0.025mm (0, 0) = bottom left +Y = up +X = right
 *
 *	@author Greger Stolt Nilsen <gregersn@gmail.com>
 */
public class GraphicsHPGL extends PGraphics
{
	public boolean debug = false;
	
	/** The name the plotter returns on id request. */
	public String plotterID;

	// Using custom serial communication handler class
	protected SerialHandler serial;
	
	/// Number of the serial port, currently not used, first one is picked. 
	protected int port;
	
	/** Stores all the transformations */
	public PMatrix2D modelView;
		
	private boolean matricesAllocated = false;
	private boolean resize = false;
	
	private int MATRIX_STACK_DEPTH = 32;
	private int transformCount = 0;
	private PMatrix2D transformStack[] = new PMatrix2D[MATRIX_STACK_DEPTH];
	
	/// Store hard clip coordinates
	private int x0, y0, x1, y1;
	
	/// Plotter class clock for timeouts
	//private int clock;
	private int timeout; // Timeout in seconds
	private int buffer_delay; // Time to wait for buffer to clear up, in seconds
	
	/**
	 * Stores all the commands for the plotter between beginDraw() and endDraw()
	 * Sent to plotter in endDraw()
	 */
	ArrayList<String> commandBuffer;
	
	// Used for debugging.
	ArrayList<String> temp;
	
	public GraphicsHPGL()
	{
		System.out.println("HPGL: Constructor");
		
		// Default timeout on a blocking read from the serial port
		this.timeout = 20;
		
		this.serial = null;
		
		this.plotterID = null;
		
		// Init new commandbuffer
		this.commandBuffer = new ArrayList<String>();
		
		this.temp = new ArrayList<String>();
		
		this.resize = false;
	}
	
	protected int write(String str)
	{
		return 0;
	}
	
    @Override
	protected void allocate()
	{
		System.out.println("HPGL: allocate()");
		
		if (!matricesAllocated)
		{
			System.out.println("HPGL: Allocating and setting up default matrices");
			// Init default matrix
			this.modelView = new PMatrix2D();
			matricesAllocated = true;
		}
		
		/*
		System.out.println("HPGL: Avaliable serial ports:");
		for (int s = 0; s < Serial.list().length; s++)
		{
			System.out.println(Serial.list()[s]);
		}
		*/
		
		if (this.serial == null)
		{
			System.out.println("HPGL: Initialize serial port");
			this.serial = new SerialHandler(this.parent);	
			this.serial.initPort();
		}

		// Only do the following if this is not a part of a resize operation
		if(this.resize == false)
		{
			//System.out.println("Getting plotter ID:");
			// Get plotter id
			while(this.plotterID == null)
			{
				this.plotterID = this.identification();
			}
			System.out.println("HPGL: Plotter id: " + this.plotterID);
	
			// Send init command
			this.serial.write((char)0x0A + "IN;" + (char)0x0A+""+(char)0x0A);
			
			// Get the size of the paper, and init the applet with new values
			this.resize = true;
			this.setWidthHeight();

			// Send default command
			this.serial.write((char)0x0A + "DF;" + (char)0x0A+""+(char)0x0A);
			
			// Send command for allocating memory in plotter
			this.serial.write((char)0x0A + "GM;" + (char)0x0A+""+(char)0x0A);
			
		}
		this.resize = false;
	}

	/// Gets the clipping coordinates, and sets them as canvas size
	protected void setWidthHeight()
	{
		this.getHardClip();
		this.width = this.x1 - this.x0;
		this.height = this.y1 - this.y0;
		this.parent.width = width;
		this.parent.height = height;
		
		width1 = this.width - 1;
		height1 = this.height - 1;
		
		//System.out.println("HPGL: Page width: " + this.width);
		//System.out.println("HPGL: Page height: " + this.height);
		
		this.parent.resize(this.width, this.height);
		
	}
	
	
    @Override
	protected void defaultSettings()
	{
		System.out.println("HPGL: defaultSettings()");
		super.defaultSettings();
		textMode = SHAPE;
	}
	
    @Override
	public void beginDraw()
	{
		System.out.println("HPGL: beginDraw()");
				
		// Add init command to command stack
		this.init();
		
		// Make sure pen is up at the beginning of plot
		this.penUp();
	}
	
    @Override
	public void endDraw()
	{
		System.out.println("HPGL: endDraw()");
		
		if (this.commandBuffer.size() <= 0)
		{
			System.out.println("HPGL WARNING: No commands");
			return;
		}

		this.penUp();
		
		/// Send all commands in command buffer to plotter
		if (this.serial == null)
		{
			// If there is no serial port open, clear all commands to prevent 
			// endless loop.
			System.out.println("HPGL ERROR: Serial port not opened");
			System.out.println("HPGL ERROR: No output method");
			this.commandBuffer.clear();
			return;			
		}
		String cmd = null;
		System.out.println("HPGL Sending commands");
		while (this.commandBuffer.size() > 0)
		{
			if (this.serial != null)
			{
				//System.out.println("Checking status");

				int stat = this.checkStatus();
				int err = 0;
				if (((stat & 32) == 32))
				{
					err = this.checkError();
					
					if ((err & 1) == 1)
					{
						System.out.println("ERROR: Unrecognized commands:");
						for(int i = 0; i < this.temp.size(); i++)
						{
							System.out.println("ERROR: " + this.temp.get(i));
						}
					}
					//TODO: Better error handling
				}
				
				//System.out.println("Getting buffer space");
				int freeBuff = this.getBufferSpace();
				
				if (((stat & 16) == 16)) // Ready to recieve data
				{
					this.temp.clear();
					//System.out.println("Ready to recieve");
					while (freeBuff > 0 && this.commandBuffer.size() > 0)
					{
						cmd = this.commandBuffer.get(0); // Get a command
						if (freeBuff > (cmd.length() + 128)) // Add 128, just to be safe
						{
							//System.out.println("Sending command: " + cmd);
							this.temp.add(cmd);
							this.serial.write((char)0x0A + cmd + (char)0x0A+""+(char)0x0A);
							freeBuff -= cmd.length();
							cmd = null;
							this.commandBuffer.remove(0);
						} 
						else
						{
						//	System.out.println("Not enough buffer space to send command");
						//	System.out.println(cmd);
							freeBuff = 0;
						}
					}
				} 
				else
				{
					System.out.println("HPGL: Delaying!");
                                        
                                        int curtime = this.parent.millis();
                                        while(this.parent.millis()-curtime < buffer_delay*1000)
                                        {
                                        
                                        }
				}
				
			}
		}
		
		this.commandBuffer.clear();
		System.out.println("HPGL: endDraw() done - whole plot sent");
	}
	
	protected Graphics2D createGraphics()
	{
		System.out.println("HPGL: createGraphics()");
		return null;
	}
	
	public void dispose()
	{
		System.out.println("HPGL: dispose()");
	}
	
	/**
	 * Don't open a window for this renderer
	 */
	public boolean displayable()
	{
		return false;
	}
	
	// Drawing functions
	
	public void endShape(int mode)
	{
		// Don't draw if there are no vertices
		if (vertexCount == 0)
		{
			//System.out.println("HPGL: No shape");
			shape = 0;
			return;
		}

		//System.out.println("HPGL: Drawing shape with " + vertexCount + " vertices");

		switch (shape)
		{
		case TRIANGLES:
			this.drawTriangles(vertices, vertexCount, mode);
			break;
		case QUADS:
			this.drawQuads(vertices, vertexCount, mode);
			break;
		case POLYGON:
		default:
			this.drawCurve(vertices, vertexCount, mode);
			break;
		}
		
		this.penUp();
		vertexCount = 0;
		shape = 0;
	}
	
	// / MATRIX STACK
	
	public void pushMatrix()
	{
		if (transformCount == transformStack.length)
		{
			throw new RuntimeException("pushMatrix() overflow");
		}
		transformStack[transformCount] = this.modelView.get();
		transformCount++;
		
	}
	
	public void popMatrix()
	{
		if (transformCount == 0)
		{
			throw new RuntimeException("HPGL: matrix stack underrun");
		}
		transformCount--;
		this.modelView = new PMatrix2D();
		for (int i = 0; i <= transformCount; i++)
		{
			this.modelView.apply(transformStack[i]);
		}
		
	}
	
	public void translate(float x, float y)
	{
		this.modelView.translate(x, y);
	}
	
	public void rotate(float angle)
	{
		this.modelView.rotate(angle);
	}
	
	// !Drawing functions
	
	// Plotter specific functions
	protected String init()
	{
		if (this.commandBuffer != null)
		{
			String cmd = "IN;";
			this.commandBuffer.add(cmd);
			return cmd;
		}
		return "";
	}
	
	protected String penUp()
	{
		if (this.commandBuffer != null)
		{
			String cmd = "PU;";
			this.commandBuffer.add(cmd);
			return cmd;
		}
		return "";
	}
	
	protected void ellipseImpl(float x, float y, float w, float h)
	{
		int steps = 36;
		float[][] points = new float[steps][2];
		for (int t = 0; t < steps; t++)
		{
			points[t][0] = w * PApplet.cos(t * (TWO_PI / (float) steps));
			points[t][1] = h * PApplet.sin(t * (TWO_PI / (float) steps));
		}
		this.drawCurve(points, steps, CLOSE);
	}
	
	protected void drawTriangles(float[][] points, int vertexCount, int mode)
	{
		if (this.commandBuffer != null)
		{
			float[][] tri = new float[3][2];
			int pp = 0;
			if (vertexCount - pp >= 3)
			{
				for (int i = 0; i < 3; i++)
				{
					tri[i][0] = points[pp][0];
					tri[i][1] = points[pp][1];
					pp++;
				}
				drawCurve(tri, 3, CLOSE);
			}
		}
	}
	
	protected void drawQuads(float[][] points, int vertexCount, int mode)
	{
		if (this.commandBuffer != null)
		{
			float[][] quad = new float[4][2];
			int pp = 0;
			if (vertexCount - pp >= 4)
			{
				for (int i = 0; i < 4; i++)
				{
					quad[i][0] = points[pp][0];
					quad[i][1] = points[pp][1];
					pp++;
				}
				drawCurve(quad, 4, CLOSE);
			}
		}
	}
	
	protected String drawCurve(float[][] points, int vertexCount, int mode)
	{
		if (this.commandBuffer != null)
		{
			String cmd;
			//cmd = "CV1,100;";
			//this.commandBuffer.add(cmd);
			PVector p = this.modelView.mult(new PVector(points[0][0],
					points[0][1]), null);
			cmd = "PU" + p.x + "," + p.y + ";";
			this.commandBuffer.add(cmd);
			/*
			 * cmd += "PD"; for(int i = 1; i < vertexCount; i++) { if(i > 1) cmd
			 * += ","; cmd += points[i][0] + ","+points[i][1]; } cmd +=";";
			 */
			for (int i = 1; i < vertexCount; i++)
			{
				p = this.modelView.mult(
						new PVector(points[i][0], points[i][1]), null);
				cmd = "PD" + p.x + "," + p.y + ";";
				this.commandBuffer.add(cmd);
				
			}
			if (mode == CLOSE)
			{
				p = this.modelView.mult(
						new PVector(points[0][0], points[0][1]), null);
				cmd = "PD" + p.x + "," + p.y + ";";
				this.commandBuffer.add(cmd);
			}
			//cmd = "CV0;";
			this.commandBuffer.add(cmd);
			
			return "morradi!";
		}
		return "";
	}
	
	protected String penUp(float x, float y)
	{
		// System.out.println("Pen up at: " + x + "," + y);
		// this.serial.write("PU" + x + "," + y + ";");
		if (this.commandBuffer != null)
		{
			String cmd = "PU" + x + "," + y + ";";
			this.commandBuffer.add(cmd);
			return cmd;
		}
		return "";
	}
	
	protected void penDown(float x, float y)
	{
		if (this.commandBuffer != null)
		{
			this.commandBuffer.add("PD" + x + ", " + y + ";");
		}
	}
	
	// Reads the hard clip area of the plotter.
	void getHardClip()
	{
		this.serial.write((char)0x0A + "OH;" + (char)0x0A+""+(char)0x0A);
		String t = null;
		while(t == null)
		{
			t = blockingReadLine(0);
		}
		System.out.println("HPGL: Hard clip: " + t);
		t = PApplet.trim(t);	
		String[] tt = PApplet.splitTokens(t, ",");
		
		x0 = Integer.parseInt(tt[0]);
		y0 = Integer.parseInt(tt[1]);
		
		x1 = Integer.parseInt(tt[2]);
		y1 = Integer.parseInt(tt[3]);
		
		/*
		 * penUp(x0, y0); penUp(x0, y1); penUp(x1, y1); penUp(x1, y0); penUp(x0,
		 * y0);
		 */
	}
	
	public String getExtendedStatus()
	{
		this.serial.write((char)0x0A + (char)0x1B + ".O;" + (char)0x0A+""+(char)0x0A);
		String t = blockingReadLine(0);
		// if(t!=null) System.out.println(t);
		return t;
	}
	
	private int getBufferSpace()
	{
		this.serial.write((char)0x0A + "" + (char)0x1B + ".B;" + (char)0x0A+""+(char)0x0A);
		String t = blockingReadLine(0);
		if (t == null)
			return 0;
		t = PApplet.trim(t);
		//System.out.println("Free buffer space: " + t);
		int s = 0;
		try
		{
			s = Integer.parseInt(t);
		} catch (Exception ex)
		{
			return 0;
		}
		return s;
	}
	
	public int checkError()
	{
		int e = getError();
		if ((e & 1) == 1)
			System.out.println("HPGL checkError: unrecognized command");
		if ((e & 2) == 2)
			System.out.println("HPGL checkError: Wrong number of parameters");
		if ((e & 4) == 4)
			System.out.println("HPGL checkError: Out-of-range parameter");
		if ((e & 8) == 8)
			System.out.println("HPGL checkError: unused");
		if ((e & 16) == 16)
			System.out.println("HPGL checkError: unknown character set");
		if ((e & 32) == 32)
			System.out.println("HPGL checkError: position overflow");
		if ((e & 64) == 64)
			System.out.println("HPGL checkError: buffer overflow");
		if ((e & 128) == 128)
			System.out.println("HPGL checkError: not used");
		
		return e;
	}
	
	private int getError()
	{
		this.serial.write((char)0x0A + "OE;" + (char)0x0A+""+(char)0x0A);
		String t = blockingReadLine(0);
		t = PApplet.trim(t);
		int s = 0;
		try
		{
			s = Integer.parseInt(t);
		} catch (Exception ex)
		{
			System.out.println("HPGL getError() exception: " + ex);
			return 0;
		}
		return s;
	}
	
	private int checkStatus()
	{
		int s;
		
		s = getState();
		
		if ((s & 2) == 2)
		{
			//System.out.println("P2 or P1 changed");
			//getP1andP2();
		}
		if ((s & 4) == 4)
		{
			//System.out.println("Digitized point read");
		}
		if ((s & 8) == 8)
		{
			System.out.println("HPGL Initialized");
		}
		if((s&16) == 16) 
		{
			//System.out.println("Ready to recieve data");
		}
		if ((s & 32) == 32)
		{
			System.out.println("HPGL There is an error");
		}
		
		return s;
	}
	
	private String identification()
	{
		this.serial.write((char)0x0A + "OI;" + (char)0x0A+""+(char)0x0A);
		String t = blockingReadLine(0);
		//if (t != null)
			//System.out.println(t);
		return t;
	}
	
	public String getP1andP2()
	{
		this.serial.write((char)0x0A + "OP;" + (char)0x0A+""+(char)0x0A);
		String t = blockingReadLine(0);
		if (t != null)
			System.out.println(t);
		return t;
	}
	
	private int getState()
	{
		this.serial.write((char)0x0A + "OS;" + (char)0x0A+""+(char)0x0A);
		int s = -1;
		String t = blockingReadLine(0);
		if (t != null)
		{
			t = PApplet.trim(t);
			try
			{
				s = Integer.parseInt(t);
			}
			catch(Exception ex)
			{
				System.out.println("getState() exception: " + ex);
			}
		}
		return s;
	}
	String blockingReadLine(int timeout)
	{
		if (timeout <= 0)
			timeout = this.timeout;
		
		int c = this.parent.millis();

		while(this.serial.availableLines() < 1)
		{
			this.serial.available();
			if(this.parent.millis()-c > this.timeout*1000)
			{
				System.out.println("blockingReadLine timeout reached");
				return null;
			}
		}
		return this.serial.popLine();
	}
	String blockingRead(int timeout)
	{
		int in = 0;
		String instring ="";
		while((in = this.serial.available()) > 0)
		{
			//System.out.println(in);
			String tstring = serial.readStringUntil(0x0d);
			if(tstring != null)
			{
				instring += tstring;
			}
			if(instring.length() > 0)
			{
				//System.out.println(instring);
				//System.out.println(PApplet.hex(instring.charAt(instring.length()-1)));
			}
		}
		if(instring.length() > 0)
		{
			return instring;
		}
		return null;
		
	}
	
	// Utility functions, not plotter specific
	String blockingRead2(int timeout)
	{
		if (timeout <= 0)
			timeout = this.timeout;
		
		int c = this.parent.millis();
		String inBuffer = "";
		byte[] byteBuffer = new byte[4096];
		while ((this.parent.millis() - c) / 1000 < timeout)
		{
			int av = this.serial.available();
			// System.out.println(av);
			if (av > 0)
			{
				while((av = this.serial.available()) > 0)
				{
					System.out.println("Reading: " + PApplet.str(av) + " bytes");
					//serial.readBytes(byteBuffer);
					if(byteBuffer != null)
					{
						String in = new String(byteBuffer);
						//System.out.println("Read: " + in.length() + " bytes");
						System.out.println(in);
//						System.out.println(PApplet.hex(in.charAt(in.length()-2)));
						//System.out.println(PApplet.hex(serial.lastChar()));
					}
				}
				if(byteBuffer != null)
				{
					System.out.println("Done reading for now");
				}
				/*inBuffer += this.serial.readStringUntil(0x0d);
				System.out.println(inBuffer);
				if ((inBuffer != null)) // || (inBuffer.charAt(inBuffer.length()-1) == 0x0D))
				{
					System.out.println("Inbuffer: " + inBuffer);
					return inBuffer;
				}*/
				
			}
		}
		System.out.println("BlockingRead() - timeout reached");
		return null;
	}
	
	protected void nope(String function)
	{
		throw new RuntimeException("No " + function + "() for GraphicsHPGL");
	}
	
	// Functions that doesn't exist
	
	public void loadPixels()
	{
		nope("loadPixels");
	}
	
	public void updatePixels()
	{
		nope("updatePixels");
	}
}
