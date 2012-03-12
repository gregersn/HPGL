/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gsn.hpgl;


/**
 *
 * @author greger
 */
import java.util.ArrayList;
import processing.core.PApplet;
import processing.serial.Serial;


public class SerialHandler 
{

    /**
    * SerialHandler constructor
    * @param parent PApplet
    */
	protected PApplet parent;
	protected Serial serial;
	protected int port;
	protected String inBuffer;
	protected ArrayList<String> input;
	protected char separator;
	
	
	public SerialHandler(PApplet parent)
	{
		this(parent, 0);
	}
	
	public SerialHandler(PApplet parent, int port)
	{
		this.parent = parent;
		this.port = port;	
		this.serial = null;
		this.inBuffer = "";
		this.input = new ArrayList<String>();
		this.separator = (char)0x0D;
	}
	
	public int initPort()
	{
		if(this.serial == null)
		{
			this.serial = new Serial(this.parent, Serial.list()[this.port], 9600, 'N', 8, 1.0f);
		}
		
		return 0;
	}
	
	public int write(String str)
	{
		this.serial.write(str.getBytes());
		return this.available();
	}

	public int available()
	{		
		while(this.serial.available() > 0)
		{
			inBuffer += serial.readString();
		}
		if(inBuffer.indexOf(this.separator) > -1)
		{
				//System.out.println("SerialHandler: processing!");
				this.process();
		}
		return inBuffer.length();
	}
	public int availableLines()
	{
		return this.input.size();
	}
	
	public String popLine()
	{
		if(this.input.size() > 0)
		{
			String t = this.input.get(0);
			this.input.remove(0);
			return t;
		}
		return null;
	}
	void process()
	{
		//System.out.println("SERVER NetCommands, process(): Processing input: " + inBuffer);
		String t;
		int idx = 0;
	
		while((idx = inBuffer.indexOf(this.separator)) > -1)
		{
			t = inBuffer.substring(0, idx);
			//System.out.println("Found line: " + t);
			this.input.add(t);
			inBuffer = inBuffer.substring(idx+1);
		}
		//System.out.println("SERVER, NetCommands, PRocess: Left over input: " + inBuffer);
	}

	public String readStringUntil(int b)
	{
		String t = "";
		for(int i = 0; i < this.inBuffer.length(); i++)
		{
			t += this.inBuffer.charAt(i);
			if(this.inBuffer.charAt(i) == b)
			{
				this.inBuffer = this.inBuffer.substring(i);
				return t;
			}
		}
		this.inBuffer = "";
		if(t.length() > 0) return t;
		
		return null;
	}
}
