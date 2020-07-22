package org.apache.flink.connectors.e2e.kafka;

import org.apache.flink.connectors.e2e.common.AbstractSourceSinkCombinedE2E;
import org.apache.flink.connectors.e2e.common.external.ExternalSystem;
import org.apache.flink.connectors.e2e.common.external.KafkaContainerizedExternalSystem;
import org.apache.flink.connectors.e2e.common.util.Dataset;
import org.apache.flink.connectors.e2e.common.util.FlinkContainers;

import java.io.File;
import java.nio.file.Paths;

public class KafkaSourceSinkCombinedE2E extends AbstractSourceSinkCombinedE2E {

	private KafkaContainerizedExternalSystem kafka;
	File sourceFile;
	File destFile;
	public static final String INPUT_FILENAME = "random.txt";
	public static final String OUTPUT_FILENAME = "output.txt";
	public static final String TOPIC = "temp";
	public static final String GROUP_ID = "kafka-e2e-test";
	public static final String END_MARK = "END";

	@Override
	public ExternalSystem createExternalSystem() {
		kafka = new KafkaContainerizedExternalSystem(flink);
		return kafka;
	}

	@Override
	public SinkJob getSinkJob() {
		return new KafkaSinkJob();
	}

	@Override
	public SourceJob getSourceJob() {
		return new KafkaSourceJob();
	}

	@Override
	public void initResources() throws Exception {
		// Prepare random files
		sourceFile = new File(flink.getWorkspaceFolderOutside(), INPUT_FILENAME);
		Dataset.writeRandomTextToFile(sourceFile, 100, 100);
		Dataset.appendMarkToFile(sourceFile, END_MARK);
		destFile = Paths.get(flink.getWorkspaceFolderOutside().getAbsolutePath(), OUTPUT_FILENAME).toFile();

		// Prepare Kafka topic
		kafka.createTopic(TOPIC, 1, (short)1);
	}

	@Override
	public void cleanupResources() {}

	@Override
	public boolean validateResult() throws Exception {
		Dataset.appendMarkToFile(destFile, END_MARK);
		return Dataset.isSame(sourceFile, destFile);
	}

	class KafkaSinkJob extends SinkJob {
		@Override
		public File getJarFile() throws Exception {
			// Search JAR file in target directory
			String moduleName = new File(System.getProperty("user.dir")).getName();
			File targetDir = new File(System.getProperty("user.dir"), "target");
			File jobJar = null;
			for (File file : targetDir.listFiles()) {
				String filename = file.getName();
				if (filename.startsWith(moduleName) && filename.endsWith(".jar")) {
					jobJar = file;
				}
			}
			if (jobJar == null) {
				throw new Exception("Cannot find relative JAR file in the target directory. Make sure the maven project is built correctly.");
			}
			return jobJar;
		}

		@Override
		public String getMainClassName() {
			return "org.apache.flink.connectors.e2e.kafka.jobs.KafkaSinkJob";
		}

		@Override
		public String[] getArguments() {
			return new String[]{
					Paths.get(FlinkContainers.getWorkspaceDirInside().getAbsolutePath(), INPUT_FILENAME).toString(),
					KafkaContainerizedExternalSystem.HOSTNAME + ":9092",
					TOPIC,
					GROUP_ID,
					END_MARK
			};
		}
	}

	class KafkaSourceJob extends SourceJob {

		@Override
		public File getJarFile() throws Exception {
			// Search JAR file in target directory
			String moduleName = new File(System.getProperty("user.dir")).getName();
			File targetDir = new File(System.getProperty("user.dir"), "target");
			File jobJar = null;
			for (File file : targetDir.listFiles()) {
				String filename = file.getName();
				if (filename.startsWith(moduleName) && filename.endsWith(".jar")) {
					jobJar = file;
				}
			}
			if (jobJar == null) {
				throw new Exception("Cannot find relative JAR file in the target directory. Make sure the maven project is built correctly.");
			}
			return jobJar;
		}

		@Override
		public String getMainClassName() {
			return "org.apache.flink.connectors.e2e.kafka.jobs.KafkaSourceJob";
		}

		@Override
		public String[] getArguments() {
			return new String[]{
					Paths.get(FlinkContainers.getWorkspaceDirInside().getAbsolutePath(), OUTPUT_FILENAME).toString(),
					KafkaContainerizedExternalSystem.HOSTNAME + ":9092",
					TOPIC,
					GROUP_ID,
					END_MARK
			};
		}
	}
}