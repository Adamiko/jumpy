package net.pms.external.infidel.jumpy;

import java.io.PrintStream;
import java.io.IOException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import net.pms.configuration.FormatConfiguration;
import net.pms.network.HTTPResource;
import net.pms.dlna.DLNAMediaInfo;
import net.pms.dlna.DLNAResource;
import net.pms.io.OutputParams;
import net.pms.io.PipeProcess;
import net.pms.io.ProcessWrapper;
import net.pms.io.ProcessWrapperImpl;
import net.pms.io.ProcessWrapperLiteImpl;
import net.pms.encoders.Player;
import net.pms.encoders.PlayerFactory;
import net.pms.formats.Format;
import net.pms.formats.FormatFactory;

public class player extends Player {

	public String name;
	public command cmdline;
	public boolean isnative = false;
	public boolean dynamic;
	public String id;
	public int type = Format.UNKNOWN;
	public int purpose = MISC_PLAYER;
	public Format format = null;
	public String[] args;
	public String mimetype;
	public String executable = "";
	private FormatConfiguration supported;
	public String desc, supportStr, cmdStr;
	private ProcessWrapperImpl pw;
	public static PrintStream out = System.out;

	public jumpy jumpy;

	public player(jumpy jumpy, String name, String cmdline, String fmt, String mimetype, int type, int purpose, String desc) {
		isnative = true;
		init(jumpy, name, cmdline, fmt, mimetype, "f:" + fmt + " m:" + mimetype, type, purpose, desc);
	}

	public player(jumpy jumpy, String name, String cmdline, String supported, int type, int purpose, String desc) {
		String fmt = null, mimetype = null;
		if (supported.matches(".*f:\\w+.*")) {
			fmt = supported.split("f:")[1].split("\\s")[0];
		} else {
			jumpy.log("ERROR: Invalid player. Missing format (f:) in '" + supported + "'");
			return;
//			throw new IllegalArgumentException("Missing format (f:) in '" + supported + "'");
		}
		if (supported.matches(".*m:\\w+.*")) {
			mimetype = supported.split("m:")[1].split("\\s")[0];
		}
		init(jumpy, name, cmdline, fmt, mimetype, supported, type, purpose, desc);
	}

	private void init(jumpy jumpy, String name, String cmdline, String fmt, String mimetype, String supported, int type, int purpose, String desc) {

		this.jumpy = jumpy;
		this.name = name;
		this.id = name;
		this.mimetype = mimetype;
		this.type = type;
		this.purpose = purpose;
		if (!(cmdline == null || "".equals(cmdline))) {
			this.cmdline = new command(cmdline, null);
			this.dynamic = false;
		} else {
			this.dynamic = true;
		}
		final String[] fmts = fmt.split("\\|");
		this.format = FormatFactory.getAssociatedExtension("." + fmts[0]);

		// create the format if it doesn't exist
		if (this.format == null) {
			jumpy.log("adding new format " + fmt);
			final player self = this;
			this.format = new Format() {
				@Override
				public ArrayList<Class<? extends Player>> getProfiles() {
					ArrayList<Class<? extends Player>> profiles = new ArrayList<Class<? extends Player>>();
					if (type == self.type) {
//						for (String engine:PMS.getConfiguration().getEnginesAsList(PMS.get().getRegistry())) {
//							if (engine.equals(self.id)) {
						for (Player p : PlayerFactory.getPlayers()) {
							if (p.id().equals(self.id)) {
								profiles.add(0, self.getClass());
								break;
							}
						}
					}
					return profiles;
				}
				@Override
				public String[] getId() {
					return fmts;
				}
				@Override
				public boolean transcodable() {
					return true;
				}
				@Override
				public boolean ps3compatible() {
					return true;
				}
				@Override
				public Identifier getIdentifier() {
					return Identifier.CUSTOM;
				}
				@Override
				public String toString() {
					return self.id;
				}
			};
			FormatFactory.getExtensions().add(0, this.format/*.duplicate()*/);
		}
		if (this.mimetype == null) {
			this.mimetype = this.format.mimeType();
		}

		this.supported = new FormatConfiguration(Arrays.asList(supported.split(",")));
		this.supportStr = supported;
		this.desc = desc;
		this.cmdStr = cmdline;

		PlayerFactory.getAllPlayers().add(0, this);
		PlayerFactory.getPlayers().add(0, this);
		enable(true);
	}

	@Override
	public boolean excludeFormat(Format extension) {
		return ! isCompatible(extension);
	}

	@Override
	public boolean isCompatible(DLNAResource resource) {
		return (isCompatible(resource.getFormat()) || isCompatible(resource.getMedia()));
	}

	public boolean isCompatible(DLNAMediaInfo mediaInfo) {
		return supported.match(mediaInfo) != null;
	}

	public boolean isCompatible(Format format) {
		return supported.match(format.getMatchedId(), null, null) != null;
	}

	@Override
	public ProcessWrapper launchTranscode( String filename, DLNAResource dlna, DLNAMediaInfo media,
				OutputParams params ) throws IOException {

		filename = finalize(filename);

		PipeProcess pipe = new PipeProcess(System.currentTimeMillis() + id);

		HashMap<String,String> vars = new HashMap<String,String>();
		vars.put("filename", filename);
		vars.put("outfile", pipe.getInputPipe());

		cmdline.substitutions = vars;
		String[] argv = cmdline.toStrings();
		params.workDir = cmdline.startdir;
		if (cmdline.syspath != null ) {
			cmdline.env.put("PATH", cmdline.syspath);
		}
		params.env = cmdline.env;

		params.waitbeforestart = 0; // no delay when the transcode is starting
		params.minBufferSize = 1; // 1Mb of minimum buffer before sending the file

		cmdline.startAPI(jumpy.top);
		jumpy.log("%n");
		jumpy.log("starting " + name() + " player.%n%nrunning " + Arrays.toString(argv)
			+ cmdline.envInfo());

//		final player self = this;
//		pw = new ProcessWrapperImpl(argv, params, false, true) {
//			public void stopProcess() {
//				super.stopProcess();
//				self.stop();
//			}
//		};
		pw = new ProcessWrapperImpl(argv, params, false, true);
		params.maxBufferSize = 100;
		params.input_pipes[0] = pipe;
		params.stdin = null;
		ProcessWrapper pipe_process = pipe.getPipeProcess();
		pw.attachProcess(pipe_process);
		pipe_process.runInNewThread();
		final player self = this;
		pw.attachProcess(new ProcessWrapperLiteImpl(null) {
			public void stopProcess() {
				self.stop();
			}
		});
		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {}
		pipe.deleteLater();

		pw.runInNewThread();
		return pw;
	}

	public void stop() {
		if (! pw.isDestroyed()) {
			cmdline.stopAPI();
			List<String> results;
			if ((results = pw.getResults()) != null) {
				for (String line : results) {
					out.println(line);
				}
			}
		}
	}

	public void setCommand(String cmd) {
		this.cmdline = new command(cmd, null);
	}

	public String finalize(String uri) {
		if (dynamic || cmdline == null) {
			cmdline = new command(uri, null);
		}
		return uri;
	}

	public boolean enable(boolean on) {
		PmsConfiguration configuration = PMS.getConfiguration();
		List<String> engines = configuration.getEnginesAsList(PMS.get().getRegistry());
		int index = engines.indexOf(id);
		boolean remove = (!on && index > -1) || (on && index > 0);
		boolean add = (on && index != 0);
		if (remove) {
			engines.removeAll(Arrays.asList(id));
		}
		if (add) {
			engines.add(0, id);
		}
		if (add || remove) {
			configuration.setEnginesAsList(new ArrayList(engines));
			jumpy.log((on ? "en" : "dis") + "abling " + id + " player.");
			return true;
		}
		return false;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public int type() {
		return type;
	}

	@Override
	public int purpose() {
		return purpose;
	}

	@Override
	public String[] args() {
		return args;
	}

	@Override
	public String mimeType() {
		return mimetype;
	}

	@Override
	public String executable() {
		return executable;
	}

	@Override
	public JComponent config() {
		return config.playerPanel(this, true);
	}
}
