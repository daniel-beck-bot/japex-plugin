package hudson.plugins.japex;

import com.sun.japex.RegressionDetector;
import com.sun.japex.report.TestSuiteReport;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Build;
import hudson.model.BuildListener;
import hudson.model.Project;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Mailer;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.types.FileSet;
import org.kohsuke.stapler.StaplerRequest;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.QueryParameter;

/**
 * Records the japex test report for builds.
 *
 * @author Kohsuke Kawaguchi
 */
public class JapexPublisher extends Recorder {
    /**
     * Relative path to the Japex XML report files.
     */
    private String includes;

    private boolean trackRegressions;
    private double regressionThreshold;
    private String regressionAddress;

    public String getIncludes() {
        return includes;
    }

    public void setIncludes(String includes) {
        this.includes = includes;
    }

    public boolean isTrackRegressions() {
        return trackRegressions;
    }

    public void setTrackRegressions(boolean trackRegressions) {
        this.trackRegressions = trackRegressions;
    }

    public double getRegressionThreshold() {
        return regressionThreshold;
    }

    public void setRegressionThreshold(double regressionThreshold) {
        this.regressionThreshold = regressionThreshold;
    }

    public String getRegressionAddress() {
        return regressionAddress;
    }

    public void setRegressionAddress(String regressionAddress) {
        this.regressionAddress = Util.fixEmpty(Util.fixNull(regressionAddress).trim());
    }

    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        if (!(build instanceof Build)) return true;
        listener.getLogger().println("Recording japex reports "+includes);

        org.apache.tools.ant.Project antProject = new org.apache.tools.ant.Project();

        File outDir = getJapexReport(build);
        outDir.mkdir();

        int numFiles = build.getWorkspace().copyRecursiveTo(includes, new FilePath(outDir));
        if(numFiles==0) {
            listener.error("No matching file found. Configuration error?");
            build.setResult(Result.FAILURE);
            return true;
        }

        // work with files copied to the local dir
        FileSet fs = new FileSet();
        fs.setDir(outDir);
        DirectoryScanner ds = fs.getDirectoryScanner(antProject);
        String[] includedFiles = ds.getIncludedFiles();

        File prevDir = getPreviousJapexReport(build);
        boolean hasRegressionReport = false;

        for (String f : includedFiles) {
            File file = new File(ds.getBasedir(),f);

            if(file.lastModified()<build.getTimestamp().getTimeInMillis()) {
                listener.getLogger().println("Ignoring old file: "+file);
                continue;
            }

            listener.getLogger().println(file);

            String configName;

            try {
                TestSuiteReport rpt = new TestSuiteReport(file);
                configName = rpt.getParameters().get("configFile").replace('/','.');
            } catch (Exception e) {
                // TestSuiteReport ctor does throw RuntimeException
                e.printStackTrace(listener.error(e.getMessage()));
                continue;
            }

            // archive the report file
            Util.copyFile(file,new File(outDir,configName));

            // compute the regression
            File previousConfig = new File(prevDir,configName);
            if(previousConfig.exists()) {
                try {
                    File regressionFile = new File(outDir, configName + ".regression");

                    RegressionDetector regd = new RegressionDetector();
                    regd.setOldReport(previousConfig);
                    regd.setNewReport(file);
                    regd.setThreshold(regressionThreshold);
                    regd.generateXmlReport(regressionFile);
                    hasRegressionReport = true;

                    if(trackRegressions && regd.checkThreshold(new StreamSource(regressionFile))) {
                        // regression detected
                        listener.getLogger().println("Regression detected to "+configName);
                        listener.getLogger().println("Notifying "+regressionAddress);
                        build.setResult(Result.UNSTABLE);

                        StringWriter html = new StringWriter();
                        regd.generateHtmlReport(new StreamSource(regressionFile),new StreamResult(html));
                        sendNotification(build,listener,html.toString());
                    }
                } catch (IOException e) {
                    e.printStackTrace(listener.error("Failed to compute japex regression report for "+configName));
                }
            }
        }

        if(hasRegressionReport)
            build.getActions().add(new JapexReportBuildAction((Build)build)); // Type checked above

        return true;
    }

    private void sendNotification(AbstractBuild<?,?> build, BuildListener listener, String payload) {
        try {
            Message msg = new MimeMessage(Mailer.descriptor().createSession());
            msg.setRecipients(Message.RecipientType.TO,
                    InternetAddress.parse(getRegressionAddress(), false));
            msg.setFrom(new InternetAddress(Mailer.descriptor().getAdminAddress()));
            msg.setSubject("Japex performance regression in "+build.getProject().getFullDisplayName()+' '+build.getDisplayName());
            msg.setText(payload);
            msg.setHeader("Content-Type", "text/html");

            Transport.send(msg);
        } catch (MessagingException e) {
            e.printStackTrace(listener.error("Failed to send out Japex notification e-mail"));
        }
    }

    /**
     * Computes the archive of the last Japex run.
     */
    private File getPreviousJapexReport(AbstractBuild<?,?> build) {
        build = build.getPreviousNotFailedBuild();
        if(build==null)     return null;
        else    return getJapexReport(build);
    }

    /**
     * Gets the directory to store report files
     */
    static File getJapexReport(AbstractBuild<?,?> build) {
        return new File(build.getRootDir(),"japex");
    }

    @Override
    public Action getProjectAction(AbstractProject<?,?> project) {
        return project instanceof Project ? new JapexReportAction((Project)project) : null;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public BuildStepDescriptor<Publisher> getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        public DescriptorImpl() {
            super(JapexPublisher.class);
        }

        public String getDisplayName() {
            return "Record Japex test report";
        }

        @Override
        public String getHelpFile() {
            return "/plugin/japex/help.html";
        }

        @Override
        public Publisher newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            JapexPublisher pub = new JapexPublisher();
            req.bindParameters(pub,"japex.");
            if(pub.isTrackRegressions()) {
                // make sure both the threshold and address are given
                if(pub.getRegressionAddress()==null)
                    throw new FormException("No e-mail address is set","japex.trackRegressions");
                try {
                    InternetAddress.parse(pub.getRegressionAddress(), false);
                } catch (AddressException e) {
                    throw new FormException("Invalid e-mail format",e,"japex.trackRegressions");
                }
            }
            return pub;
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return Project.class.isAssignableFrom(jobType);
        }

        //
        // web methods
        //

        /**
         * Checks if the e-mail address is valid
         */
        public FormValidation doCheckAddress(@QueryParameter final String value) {
            try {
                InternetAddress.parse(value,true);
                return FormValidation.ok();
            } catch (AddressException e) {
                return FormValidation.error("Not a valid e-mail address(es)");
            }
        }
    }
}
