package org.jenkinsci.plugins.perfci.monitoring;

import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Describable;
import hudson.model.AbstractBuild;
import hudson.tasks.Builder;
import hudson.util.FormValidation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.InMemorySourceFile;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Sample {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked and a new
 * {@link NmonMonitor} is created. The created instance is persisted to the
 * project configuration XML by using XStream, so this allows you to use
 * instance fields (like {@link #name}) to remember the configuration.
 *
 * <p>
 * When a build is performed, the
 * {@link #perform(AbstractBuild, Launcher, BuildListener)} method will be
 * invoked.
 *
 * @author Kohsuke Kawaguchi
 */
public class NmonMonitor implements ResourceMonitor,
		Describable<ResourceMonitor>, ExtensionPoint {
	private static final Logger LOGGER = Logger.getLogger(NmonMonitor.class
			.getName());

	private final String host;
	private final String name;
	private final String password;
	private final String interval;
	private final String outputPath;
	// private Object monfile;
	private final static int TIMEOUT = 30000;
	private final static int MAX_TRIES = 5;

	// Fields in config.jelly must match the parameter names in the
	// "DataBoundConstructor"
	@DataBoundConstructor
	public NmonMonitor(String host, String name, String password,
			String interval, String outputPath) {
		this.host = host;
		this.name = name;
		this.password = password;
		this.interval = interval;
		this.outputPath = outputPath;
	}

	/**
	 * We'll use this from the <tt>config.jelly</tt>.
	 */
	public String getName() {
		return name;
	}

	public String getPassword() {
		return password;
	}

	public String getHost() {
		return host;
	}

	public String getInterval() {
		return interval;
	}

	@Override
	public boolean start(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws Exception {
		for (int i = 0; i < MAX_TRIES;) {
			LOGGER.info("Starting NMON monitor... (try " + ++i + " of "
					+ MAX_TRIES + ")");
			if (tryStart(build)) {
				LOGGER.info("NMON monitor started.");
				return true;
			}
			LOGGER.warning("Fail to start NMON monitor.");
		}
		LOGGER.warning("Cannot start NMON monitor. Give up.");
		return false;
	}

	private boolean tryStart(AbstractBuild<?, ?> build) throws IOException {
		String projectDir = getProjectDir(build);
		SSHClient client = new SSHClient();
		client.addHostKeyVerifier(new PromiscuousVerifier());
		try {
			client.connect(host);
			if (password == null || password.isEmpty())
				client.authPublickey(name);
			else
				client.authPassword(name, password);
			Session.Command cmd;
			Session session = client.startSession();

			cmd = session.exec("ls -lrt /tmp/jenkins-perfci/bin/nmon");
			cmd.join(TIMEOUT, TimeUnit.MILLISECONDS);
			session.close();
			if (cmd.getExitStatus() == 2) {
				session = client.startSession();
				cmd = session.exec("mkdir -p /tmp/jenkins-perfci/bin");
				cmd.join(TIMEOUT, TimeUnit.MILLISECONDS);
				session.close();
				if (cmd.getExitStatus() != 0)
					return false;
				final URL nmonfile = getClass()
						.getResource(
								"/org/jenkinsci/plugins/perfci/monitoring/NmonMonitor/nmon");
				final InputStream in = nmonfile.openStream();
				final ByteArrayOutputStream out = new ByteArrayOutputStream();
				org.apache.commons.io.IOUtils.copy(in, out);
				in.close();
				final byte[] bytes = out.toByteArray();
				out.close();
				client.newSCPFileTransfer().upload(new InMemorySourceFile() {
					public String getName() {
						return "nmon";
					}

					public long getLength() {
						return bytes.length;
					}

					public InputStream getInputStream() throws IOException {
						return new ByteArrayInputStream(bytes);
					}
				}, "/tmp/jenkins-perfci/bin/");
			}
			String remoteLogDir = getOutputDir(build);
			session = client.startSession();
			cmd = session.exec("mkdir -p '"
							+ remoteLogDir
							+ "' && chmod +x /tmp/jenkins-perfci/bin/nmon && /tmp/jenkins-perfci/bin/nmon -f -t -s"
							+ Integer.parseInt(interval) + " -c64080 -m "
							+ remoteLogDir);
			cmd.join(TIMEOUT, TimeUnit.MILLISECONDS);
			session.close();
			if (cmd.getExitStatus() != 0)
				return false;
			session = client.startSession();
			cmd = session
					.exec("sleep 3 && ps -ef | grep /tmp/jenkins-perfci/bin/[n]mon | grep '"
							+ projectDir + "' | awk '{print $2}'");
			cmd.join(TIMEOUT, TimeUnit.MILLISECONDS);
			session.close();
			if (cmd.getExitStatus() != 0)
				return false;
			if (IOUtils.readFully(cmd.getInputStream()).toString().trim()
					.isEmpty()) {
				return false;
			}
			return true;
		} catch (IOException ex) {
			return false;
		} finally {
			client.close();
		}
	}

	private String getOutputDir(AbstractBuild<?, ?> build) {
		return "/tmp/jenkins-perfci/jobs/" + build.getProject().getName() + "/"
				+ build.getId() + "/monitoring";
	}

	private String getProjectDir(AbstractBuild<?, ?> build) {
		return "/tmp/jenkins-perfci/jobs/" + build.getProject().getName();
	}

	@Override
	public boolean stop(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws IOException {
		for (int i = 0; i < MAX_TRIES;) {
			LOGGER.info("Stopping NMON monitors... (try " + ++i + " of "
					+ MAX_TRIES + ")");
			if (tryStop(build)) {
				LOGGER.info("NMON monitors stopped.");
				return true;
			}
			LOGGER.warning("Fail to stop NMON monitors.");
		}
		LOGGER.warning("Cannot stop NMON monitors. Give up.");
		return false;
	}

	public boolean tryStop(AbstractBuild<?, ?> build) throws IOException {
		String projectDir = getProjectDir(build);
		SSHClient client = new SSHClient();
		try {
			client.addHostKeyVerifier(new PromiscuousVerifier());
			client.connect(host);
			if (password == null || password.isEmpty())
				client.authPublickey(name);
			else
				client.authPassword(name, password);
			Session.Command cmd;
			Session session = client.startSession();
			cmd = session
					.exec("sync && kill `ps -ef | grep /tmp/jenkins-perfci/bin/[n]mon | grep '"
							+ projectDir + "' | awk '{print $2}'`");
			cmd.join(TIMEOUT, TimeUnit.MILLISECONDS);
			session.close();
			if (cmd.getExitStatus() != 0 && cmd.getExitStatus() != 1)
				return false;
			session = client.startSession();
			cmd = session
					.exec("sleep 3 && ps -ef | grep /tmp/jenkins-perfci/bin/[n]mon | grep '"
							+ projectDir + "' | awk '{print $2}'");
			cmd.join(TIMEOUT, TimeUnit.MILLISECONDS);
			session.close();
			if (cmd.getExitStatus() != 0)
				return false;
			if (!IOUtils.readFully(cmd.getInputStream()).toString().trim()
					.isEmpty()) {
				LOGGER.info("Cannot stop, try 'kill -s INT'...");
				session = client.startSession();
				cmd = session
						.exec("sync && kill -s INT `ps -ef | grep /tmp/jenkins-perfci/bin/[n]mon | grep '"
								+ projectDir + "' | awk '{print $2}'`");
				cmd.join(TIMEOUT, TimeUnit.MILLISECONDS);
				session.close();
				if (cmd.getExitStatus() != 0 && cmd.getExitStatus() != 1)
					return false;
				session = client.startSession();
				cmd = session
						.exec("sleep 5 && ps -ef | grep /tmp/jenkins-perfci/bin/[n]mon | grep '"
								+ projectDir + "' | awk '{print $2}'");
				cmd.join(TIMEOUT, TimeUnit.MILLISECONDS);
				session.close();
				if (cmd.getExitStatus() != 0)
					return false;
				if (!IOUtils.readFully(cmd.getInputStream()).toString().trim()
						.isEmpty()) {
					LOGGER.info("Cannot stop, try 'kill -s KILL'...");
					session = client.startSession();
					cmd = session
							.exec("sync && kill -s KILL `ps -ef | grep /tmp/jenkins-perfci/bin/[n]mon | grep '"
									+ projectDir + "' | awk '{print $2}'`");
					cmd.join(TIMEOUT, TimeUnit.MILLISECONDS);
					session.close();
					if (cmd.getExitStatus() != 0 && cmd.getExitStatus() != 1)
						return false;
					session = client.startSession();
					cmd = session
							.exec("sleep 5 && ps -ef | grep /tmp/jenkins-perfci/bin/[n]mon | grep '"
									+ projectDir + "' | awk '{print $2}'");
					cmd.join(TIMEOUT, TimeUnit.MILLISECONDS);
					session.close();
					if (cmd.getExitStatus() != 0)
						return false;
					if (!IOUtils.readFully(cmd.getInputStream()).toString()
							.trim().isEmpty()) {
						LOGGER.info("Oops! Still cannot stop. That was strange. Give up this try.");
						return false;
					}
				}
			}

			String relativePathForNMONFiles = outputPath == null
					|| outputPath.isEmpty() ? "monitoring" : outputPath;
			String pathOnAgent = relativePathForNMONFiles.startsWith("/")
					|| relativePathForNMONFiles.startsWith("file:") ? relativePathForNMONFiles
					: build.getWorkspace().getRemote() + File.separator
							+ relativePathForNMONFiles;
			LOGGER.info("Copy NMON logs to agent '" + pathOnAgent + "'...");
			new File(pathOnAgent).mkdirs();
			client.newSCPFileTransfer().download(
					getOutputDir(build) + File.separator,
					pathOnAgent + File.separator);
			return true;
		} catch (IOException ex) {
			return false;
		} finally {
			client.close();
		}
	}

	@Extension
	public static class DescriptorImpl extends ResourceMonitorDescriptor {

		@Override
		public String getDisplayName() {
			return "Nmon Monitor";
		}

		public FormValidation doCheckDelimiter(@QueryParameter String delimiter) {
			if (delimiter == null || delimiter.isEmpty()) {
				return FormValidation.error("Delimier can't be empty");
			}
			return FormValidation.ok();
		}

		public FormValidation doCheckPattern(@QueryParameter String pattern) {
			if (pattern == null || pattern.isEmpty()) {
				FormValidation.error("Pattern can't be empty");
			}

			return null;
		}

		private void validatePresent(Set<String> missing, String pattern,
				String string) {
			if (!pattern.contains(string)) {
				missing.add(string);
			}
		}
	}

	@Override
	public void collect(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) {

	}

	@Override
	public boolean isRuning(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) {
		return false;
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) Jenkins.getInstance().getDescriptor(
				NmonMonitor.class);
	}

	public String getOutputPath() {
		return outputPath;
	}
}
