package com.cg.osce.apibuilder.service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Set;

import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.cg.osce.apibuilder.APIBuilderController;
import com.cg.osce.apibuilder.FormWrapper;
import com.cg.osce.apibuilder.exception.APIBuilderException;
import com.cg.osce.apibuilder.pojo.Constants;
import com.cg.osce.apibuilder.pojo.SwaggerSchema;
import com.cg.osce.apibuilder.pojo.SwaggerSchema.Info;
import com.cg.osce.apibuilder.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature;

@Service
public class ArtifactGeneratorServiceImpl implements IArtifactGeneratorService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactGeneratorServiceImpl.class);

	@Autowired
	StorageService storageService;

	@Autowired
	PojoHelper generator;

	@Autowired
	ArtifactHelper artifactGenerator;

	@Value("${package.name}")
	private String packageName;

	static final JsonNodeFactory factory = JsonNodeFactory.instance;
	private static ObjectMapper yamlMapper = new ObjectMapper(
			new YAMLFactory().disable(Feature.WRITE_DOC_START_MARKER));
	private static ObjectMapper jsonMapper = new ObjectMapper();

	@Override
	public String handleFileUpload(FormWrapper formWrapper, RedirectAttributes redirectAttributes)
			throws APIBuilderException {

		String artifactPath;
		clearDirectories();

		storageService.store(formWrapper.getFile());

		if (formWrapper.getFile().getOriginalFilename().endsWith(".xsd")) {
			generator.createPojoForXSD(formWrapper.getFile());
		} else if (formWrapper.getFile().getOriginalFilename().endsWith(".json")) {
			generator.convertPojoForJSON(formWrapper.getFile());
		}

		compilePojos();

		artifactPath = generateArtifact(formWrapper);

		return artifactPath;

	}

	void clearDirectories() throws APIBuilderException {
		String userDir = System.getProperty("user.dir").replace("\\", "/");
		File schemaDirectory = new File(userDir + "/xsd/");
		File entityDirectory = new File(userDir + "/src/main/java/com/cg/osce/apibuilder/entity");
		File entityClassDirectory = new File(userDir + "/target/classes/com/cg/osce/apibuilder/entity");
		try {
			if (entityClassDirectory.exists()) {
				FileUtils.cleanDirectory(entityClassDirectory);
			}
			if (entityDirectory.exists()) {
				FileUtils.cleanDirectory(entityDirectory);
			}
			if (schemaDirectory.exists()) {
				FileUtils.cleanDirectory(schemaDirectory);
			}

		} catch (IOException e) {
			LOGGER.error(e.getLocalizedMessage(), e);
			throw new APIBuilderException(128, "Failed to clear directories");
		}
	}

	void compilePojos() throws APIBuilderException {
		String userDir = System.getProperty("user.dir").replace("\\", "/");
		String[] command = { "cmd", };
		String jarPath = null;
		Process p;
		try {
			p = Runtime.getRuntime().exec(command);

			new Thread(new ErrorPipe(p.getErrorStream(), System.err)).start();
			new Thread(new SyncPipe(p.getInputStream(), System.out)).start();
			PrintWriter stdin = new PrintWriter(p.getOutputStream());

			stdin.println("cd src/main/java/com/cg/osce/apibuilder/entity/");

			jarPath = "-classpath \"" + System.getProperty("java.class.path") + "\"";
			stdin.println("javac " + jarPath + " *.java");
			stdin.println("move *.class \"" + userDir + "/target/classes/com/cg/osce/apibuilder/entity/\"");
			stdin.close();
			if (p.getErrorStream().read() != -1) {
				throw new APIBuilderException(128, "Provided Schema is not valid");
			}
		} catch (IOException e) {
			LOGGER.error(e.getLocalizedMessage(), e);
			throw new APIBuilderException(128, "Failed to Process provided schema");
		}
	}

	public String generateArtifact(FormWrapper request) throws APIBuilderException {

		SwaggerSchema artifact = new SwaggerSchema();
		artifact.setSwagger(request.getSwaggerVersion());
		Info info = new Info();
		info.setVersion(request.getSwaggerVersion());

		info.setTitle(request.getTitle());
		info.setDescription(request.getDescription());
		artifact.setInfo(info);
		artifact.setBasePath(request.getBasepath());
		artifact.setHost(request.getHost());
		Set<Class<? extends Object>> entities = artifactGenerator.getClassDetails();

		ObjectNode paths = factory.objectNode();
		for (Class<? extends Object> entity : entities) {
			if ("ObjectFactory".equals(entity.getSimpleName()) || "package-info".equals(entity.getSimpleName()))
				continue;

			ObjectNode path = factory.objectNode();

			path.putPOJO("get", artifactGenerator.createDefaultGet(entity, factory));
			path.putPOJO("post", artifactGenerator.createDefaultPost(entity, factory));
			path.putPOJO("put", artifactGenerator.createDefaultPut(entity, factory));
			path.putPOJO("delete", artifactGenerator.createDefaultDelete(entity, factory));
			paths.set("SLASH" + entity.getSimpleName().toLowerCase(), path);
		}
		artifact.setSchemes(new String[] { "http", "https" });

		artifact.setPaths(paths);

		artifact.setDefinitions(artifactGenerator.creteDefinitions(factory));

		return createArtifactFile(request.getArtifactType(), request.getTitle(), artifact);

	}

	String createArtifactFile(String artifactType, String title, SwaggerSchema artifact) throws APIBuilderException {

		FileWriter fw;
		String fileName = null;
		Path path = null;
		title = title.replace(" ", "_");
		try {
			if ("JSON".equals(artifactType)) {
				fileName = title + ".json";
				fw = new FileWriter(Constants.FILELOCATION + fileName);
				fw.write(jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(artifact).replace("SLASH", "/"));
				fw.close();
				path = storageService.load(fileName);
			} else {
				fileName = title + ".yaml";
				fw = new FileWriter(Constants.FILELOCATION + fileName);
				fw.write(yamlMapper.writeValueAsString(artifact).replace("SLASH", "/"));
				fw.close();
				path = storageService.load(fileName);
			}

		} catch (Exception e) {
			LOGGER.error(e.toString(), e);
			throw new APIBuilderException(123, "Failed to generate API Artifact");
		}

		return MvcUriComponentsBuilder
				.fromMethodName(APIBuilderController.class, "serveFile", path.getFileName().toString()).build()
				.toString();

	}

}
