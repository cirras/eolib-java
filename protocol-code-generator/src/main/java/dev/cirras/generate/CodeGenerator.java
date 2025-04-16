package dev.cirras.generate;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dev.cirras.generate.type.EnumType;
import dev.cirras.generate.type.StructType;
import dev.cirras.generate.type.Type;
import dev.cirras.generate.type.TypeFactory;
import dev.cirras.util.CommentUtils;
import dev.cirras.util.JavaPoetUtils;
import dev.cirras.xml.Protocol;
import dev.cirras.xml.ProtocolComment;
import dev.cirras.xml.ProtocolEnum;
import dev.cirras.xml.ProtocolPacket;
import dev.cirras.xml.ProtocolStruct;
import dev.cirras.xml.ProtocolValidationEventHandler;
import dev.cirras.xml.ProtocolValue;
import dev.cirras.xml.ProtocolXmlError;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class CodeGenerator {
  private static final Logger LOG = LogManager.getLogger(CodeGenerator.class);
  private final Path inputRoot;
  private final Path outputRoot;
  private final List<Protocol> protocolFiles;
  private final Map<ProtocolPacket, String> packetPackageNames;
  private final TypeFactory typeFactory;

  /**
   * Constructor
   *
   * @param inputRoot Path where protocol.xml files can be found
   * @param outputRoot Path where generated code should go
   */
  public CodeGenerator(Path inputRoot, Path outputRoot) {
    this.inputRoot = inputRoot;
    this.outputRoot = outputRoot;
    this.protocolFiles = new ArrayList<>();
    this.packetPackageNames = new HashMap<>();
    this.typeFactory = new TypeFactory();
  }

  public void generate() {
    try {
      indexProtocolFiles();
      generateSourceFiles();
    } finally {
      protocolFiles.clear();
      packetPackageNames.clear();
      typeFactory.clear();
    }
  }

  private void indexProtocolFiles() {
    try (Stream<Path> pathStream =
        Files.find(
            inputRoot,
            Integer.MAX_VALUE,
            (p, basicFileAttributes) -> p.getFileName().toString().equals("protocol.xml"))) {
      pathStream.forEach(this::indexProtocolFile);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private void indexProtocolFile(Path path) {
    LOG.info("Indexing {}", path);

    try {
      JAXBContext context = JAXBContext.newInstance(Protocol.class);
      Unmarshaller unmarshaller = context.createUnmarshaller();
      unmarshaller.setEventHandler(new ProtocolValidationEventHandler());

      Protocol protocol = (Protocol) unmarshaller.unmarshal(path.toFile());

      String packageName = "dev.cirras.protocol";
      String relativePath = inputRoot.relativize(path.getParent()).toString();
      String relativePackage = relativePath.replace(".", "").replace(File.separatorChar, '.');
      if (!relativePackage.isEmpty()) {
        packageName += "." + relativePackage;
      }

      protocolFiles.add(protocol);

      for (ProtocolEnum protocolEnum : protocol.getEnums()) {
        if (!typeFactory.defineCustomType(protocolEnum, packageName)) {
          throw new ProtocolXmlError(
              String.format("%s type cannot be redefined.", protocolEnum.getName()));
        }
      }

      for (ProtocolStruct protocolStruct : protocol.getStructs()) {
        if (!typeFactory.defineCustomType(protocolStruct, packageName)) {
          throw new ProtocolXmlError(
              String.format("%s type cannot be redefined.", protocolStruct.getName()));
        }
      }

      Set<String> declaredPackets = new HashSet<>();
      for (ProtocolPacket protocolPacket : protocol.getPackets()) {
        String packetIdentifier = protocolPacket.getFamily() + "_" + protocolPacket.getAction();
        if (!declaredPackets.add(packetIdentifier)) {
          throw new ProtocolXmlError(
              String.format("%s packet cannot be redefined in the same file.", packetIdentifier));
        }
        packetPackageNames.put(protocolPacket, packageName);
      }
    } catch (JAXBException e) {
      throw new ProtocolXmlError("Failed to read " + path.toString(), e);
    }
  }

  private void generateSourceFiles() {
    protocolFiles.forEach(this::generateSourceFiles);
    generatePacketInterface();
  }

  private void generateSourceFiles(Protocol protocol) {
    List<JavaFile> javaFiles = new ArrayList<>();

    protocol.getEnums().stream().map(this::generateEnum).forEach(javaFiles::add);
    protocol.getStructs().stream().map(this::generateStruct).forEach(javaFiles::add);
    protocol.getPackets().stream().map(this::generatePacket).forEach(javaFiles::add);

    for (JavaFile javaFile : javaFiles) {
      try {
        javaFile.writeToPath(outputRoot);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private void generatePacketInterface() {
    Iterator<Protocol> packetProtocols =
        protocolFiles.stream().filter(protocol -> !protocol.getPackets().isEmpty()).iterator();

    if (packetProtocols.hasNext()) {
      Protocol protocol = packetProtocols.next();

      ClassName packetInterfaceName = getPacketInterfaceName();

      TypeSpec.Builder builder = generatePacketInterfaceBuilder(protocol);
      addPacketDeserializer(builder, protocol);

      while (packetProtocols.hasNext()) {
        protocol = packetProtocols.next();
        addPacketDeserializer(builder, protocol);
      }

      JavaFile javaFile =
          JavaFile.builder(packetInterfaceName.packageName(), builder.build()).build();
      try {
        javaFile.writeToPath(outputRoot);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private JavaFile generateEnum(ProtocolEnum protocolEnum) {
    final String UNKNOWN_ENUM_VALUE = "UNRECOGNIZED";

    EnumType type = (EnumType) typeFactory.getType(protocolEnum.getName());
    String packageName = type.getPackageName();
    ClassName enumName = ClassName.get(packageName, protocolEnum.getName());

    LOG.info("Generating enum: {}", enumName);

    TypeSpec.Builder enumTypeSpec =
        TypeSpec.enumBuilder(enumName)
            .addJavadoc(
                "An enum representing expected values of {@link $L}.", enumName.simpleName())
            .addAnnotation(JavaPoetUtils.getGeneratedAnnotationTypeName())
            .addModifiers(Modifier.PUBLIC)
            .addField(int.class, "value", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(
                MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PRIVATE)
                    .addParameter(int.class, "value")
                    .addStatement("this.value = value")
                    .build())
            .addMethod(
                MethodSpec.methodBuilder("value")
                    .addJavadoc("Returns the integer value of this enumeration constant.")
                    .addJavadoc("\n\n")
                    .addJavadoc("@return the integer value of this enumeration constant")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(int.class)
                    .addStatement("return value")
                    .build());

    protocolEnum
        .getComment()
        .map(ProtocolComment::getText)
        .map(CommentUtils::formatComment)
        .ifPresent(enumTypeSpec::addJavadoc);

    enumTypeSpec.addEnumConstant(
        UNKNOWN_ENUM_VALUE,
        TypeSpec.anonymousClassBuilder("-1")
            .addJavadoc("An unrecognized value that is not represented in the enum.")
            .build());

    CodeBlock.Builder fromIntegerSwitchBlock =
        CodeBlock.builder().beginControlFlow("switch (value)");

    for (ProtocolValue protocolValue : protocolEnum.getValues()) {
      EnumType.EnumValue value =
          type.getEnumValueByOrdinal(protocolValue.getOrdinalValue())
              .orElseThrow(IllegalStateException::new);

      TypeSpec.Builder enumConstantClass =
          TypeSpec.anonymousClassBuilder("$L", value.getOrdinalValue());

      protocolValue
          .getComment()
          .map(ProtocolComment::getText)
          .ifPresent(enumConstantClass::addJavadoc);

      enumTypeSpec.addEnumConstant(value.getJavaName(), enumConstantClass.build());

      fromIntegerSwitchBlock
          .add("case $L:\n", value.getOrdinalValue())
          .indent()
          .addStatement("return $L", value.getJavaName())
          .unindent();
    }

    fromIntegerSwitchBlock
        .add("default:\n")
        .indent()
        .addStatement("return " + UNKNOWN_ENUM_VALUE)
        .unindent()
        .endControlFlow();

    enumTypeSpec
        .addMethod(
            MethodSpec.methodBuilder("get")
                .addJavadoc(
                    "Returns the corresponding {@link $L} for the specified integer value.",
                    enumName.simpleName())
                .addJavadoc("\n\n")
                .addJavadoc("@param value the integer value\n")
                .addJavadoc(
                    "@return an corresponding {@link $L} for the specified integer value\n",
                    enumName.simpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(int.class, "value")
                .returns(enumName)
                .addCode(fromIntegerSwitchBlock.build())
                .build())
        .addMethod(
            MethodSpec.methodBuilder("toString")
                .addJavadoc("Returns a string representation of the object.")
                .addJavadoc("\n\n")
                .addJavadoc("@return a string representation of the object")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("  return this.name() + \"(\" + this.value + \")\"")
                .build());

    return JavaFile.builder(packageName, enumTypeSpec.build()).build();
  }

  private JavaFile generateStruct(ProtocolStruct protocolStruct) {
    StructType type = (StructType) typeFactory.getType(protocolStruct.getName());
    String packageName = type.getPackageName();
    ClassName className = ClassName.get(packageName, protocolStruct.getName());

    LOG.info("Generating struct: {}", className);

    ObjectCodeGenerator objectCodeGenerator = new ObjectCodeGenerator(className, typeFactory);
    protocolStruct.getInstructions().forEach(objectCodeGenerator::generateInstruction);

    TypeSpec.Builder typeSpec = objectCodeGenerator.getTypeSpec();
    protocolStruct
        .getComment()
        .map(ProtocolComment::getText)
        .map(CommentUtils::formatComment)
        .ifPresent(typeSpec::addJavadoc);

    return JavaFile.builder(packageName, typeSpec.build()).build();
  }

  private EnumType getPacketFamilyType() {
    Type familyType = typeFactory.getType("PacketFamily");
    if (!(familyType instanceof EnumType)) {
      throw new CodeGenerationError("PacketFamily enum is missing");
    }
    return (EnumType) familyType;
  }

  private EnumType getPacketActionType() {
    Type actionType = typeFactory.getType("PacketAction");
    if (!(actionType instanceof EnumType)) {
      throw new CodeGenerationError("PacketAction enum is missing");
    }
    return (EnumType) actionType;
  }

  private ClassName getPacketFamilyTypeName() {
    EnumType familyType = getPacketFamilyType();
    return ClassName.get(familyType.getPackageName(), familyType.getName());
  }

  private ClassName getPacketActionTypeName() {
    EnumType actionType = getPacketActionType();
    return ClassName.get(actionType.getPackageName(), actionType.getName());
  }

  private String getFamilyJavaName(String protocolName) {
    return getPacketFamilyType()
        .getEnumValueByProtocolName(protocolName)
        .map(EnumType.EnumValue::getJavaName)
        .orElseThrow(
            () ->
                new CodeGenerationError(
                    String.format("Unknown packet family \"%s\"", protocolName)));
  }

  private String getActionJavaName(String protocolName) {
    return getPacketActionType()
        .getEnumValueByProtocolName(protocolName)
        .map(EnumType.EnumValue::getJavaName)
        .orElseThrow(
            () ->
                new CodeGenerationError(
                    String.format("Unknown packet family \"%s\"", protocolName)));
  }

  private ClassName getPacketClassName(ProtocolPacket protocolPacket) {
    String packageName = packetPackageNames.get(protocolPacket);
    String packetSuffix = makePacketSuffix(packageName);
    String simpleName = protocolPacket.getFamily() + protocolPacket.getAction() + packetSuffix;
    return ClassName.get(packageName, simpleName);
  }

  private ClassName getPacketInterfaceName() {
    String packageName = getPacketFamilyType().getPackageName();
    return ClassName.get(packageName, "Packet");
  }

  private JavaFile generatePacket(ProtocolPacket protocolPacket) {
    ClassName className = getPacketClassName(protocolPacket);

    LOG.info("Generating packet: {}", className);

    ObjectCodeGenerator objectCodeGenerator = new ObjectCodeGenerator(className, typeFactory);
    protocolPacket.getInstructions().forEach(objectCodeGenerator::generateInstruction);

    TypeName familyTypeName = getPacketFamilyTypeName();
    String familyValueJavaName = getFamilyJavaName(protocolPacket.getFamily());

    TypeName actionTypeName = getPacketActionTypeName();
    String actionValueJavaName = getActionJavaName(protocolPacket.getAction());

    TypeSpec.Builder typeSpec =
        objectCodeGenerator
            .getTypeSpec()
            .addSuperinterface(JavaPoetUtils.getPacketTypeName())
            .addMethod(
                MethodSpec.methodBuilder("packetFamily")
                    .addJavadoc("Returns the packet family associated with this type.")
                    .addJavadoc("\n\n")
                    .addJavadoc("@return the packet family associated with this type")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(familyTypeName)
                    .addStatement("return $T.$L", familyTypeName, familyValueJavaName)
                    .build())
            .addMethod(
                MethodSpec.methodBuilder("packetAction")
                    .addJavadoc("Returns the packet action associated with this type.")
                    .addJavadoc("\n\n")
                    .addJavadoc("@return the packet action associated with this type")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(actionTypeName)
                    .addStatement("return $T.$L", actionTypeName, actionValueJavaName)
                    .build())
            .addMethod(
                MethodSpec.methodBuilder("family")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(familyTypeName)
                    .addStatement("return $T.packetFamily()", className)
                    .build())
            .addMethod(
                MethodSpec.methodBuilder("action")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(actionTypeName)
                    .addStatement("return $T.packetAction()", className)
                    .build())
            .addMethod(
                MethodSpec.methodBuilder("serialize")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(JavaPoetUtils.getWriterTypeName(), "writer")
                    .addStatement("$T.serialize(writer, this)", className)
                    .build());

    protocolPacket
        .getComment()
        .map(ProtocolComment::getText)
        .map(CommentUtils::formatComment)
        .ifPresent(typeSpec::addJavadoc);

    return JavaFile.builder(className.packageName(), typeSpec.build()).build();
  }

  private TypeSpec.Builder generatePacketInterfaceBuilder(Protocol protocol) {
    EnumType familyType = getPacketFamilyType();
    ClassName familyTypeName = ClassName.get(familyType.getPackageName(), familyType.getName());
    EnumType actionType = getPacketActionType();
    ClassName actionTypeName = ClassName.get(actionType.getPackageName(), actionType.getName());

    ClassName packetInterfaceName = getPacketInterfaceName();

    LOG.info("Generating packet interface {}", packetInterfaceName);

    return TypeSpec.interfaceBuilder(packetInterfaceName)
        .addJavadoc("Object representation of a packet in the EO network protocol.")
        .addAnnotation(JavaPoetUtils.getGeneratedAnnotationTypeName())
        .addModifiers(Modifier.PUBLIC)
        .addMethod(
            MethodSpec.methodBuilder("family")
                .addJavadoc("Returns the packet family associated with this packet.")
                .addJavadoc("\n\n")
                .addJavadoc("@return the packet family associated with this packet")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(familyTypeName)
                .build())
        .addMethod(
            MethodSpec.methodBuilder("action")
                .addJavadoc("Returns the packet action associated with this packet.")
                .addJavadoc("\n\n")
                .addJavadoc("@return the packet action associated with this packet")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(actionTypeName)
                .build())
        .addMethod(
            MethodSpec.methodBuilder("serialize")
                .addJavadoc("Serializes this packet to the provided {@link EoWriter}.")
                .addJavadoc("\n\n")
                .addJavadoc("@param writer the writer that this packet will be serialized to")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter(ClassName.get("dev.cirras.data", "EoWriter"), "writer")
                .build());
  }

  private void addPacketDeserializer(TypeSpec.Builder packetInterfaceBuilder, Protocol protocol) {
    ClassName packetInterfaceName = getPacketInterfaceName();

    String packetsPackageName =
        packetPackageNames.get(
            protocol.getPackets().stream()
                .findFirst()
                .orElseThrow(
                    () ->
                        new CodeGenerationError(
                            "No packets in protocol but generating packet deserializer")));
    String packetSuffix = makePacketSuffix(packetsPackageName);

    String packetFamilyParameter = "packetFamily";
    String packetActionParameter = "packetAction";
    String eoReaderParameter = "eoReader";

    CodeBlock.Builder deserializeSwitchBlock =
        CodeBlock.builder().beginControlFlow(String.format("switch (%s)", packetFamilyParameter));

    Map<String, List<ProtocolPacket>> packetFamilies = new HashMap<>();

    protocol
        .getPackets()
        .forEach(
            packet -> {
              packetFamilies
                  .computeIfAbsent(packet.getFamily(), k -> new ArrayList<>())
                  .add(packet);
            });

    packetFamilies.forEach(
        (family, packets) -> {
          deserializeSwitchBlock.add("case $L:\n", getFamilyJavaName(family));
          deserializeSwitchBlock.indent();
          CodeBlock.Builder actionSwitchBlock =
              CodeBlock.builder().beginControlFlow("switch ($L)", packetActionParameter);

          for (ProtocolPacket packet : packets) {
            actionSwitchBlock.addStatement(
                "case $L: return $T.deserialize($L)",
                getActionJavaName(packet.getAction()),
                getPacketClassName(packet),
                eoReaderParameter);
          }

          actionSwitchBlock.endControlFlow();
          deserializeSwitchBlock.add(actionSwitchBlock.build());
          deserializeSwitchBlock.unindent();
        });

    deserializeSwitchBlock.endControlFlow();

    packetInterfaceBuilder.addMethod(
        MethodSpec.methodBuilder("deserialize" + packetSuffix)
            .addJavadoc("Deserializes this packet from the provided {@link EoReader}.")
            .addJavadoc("\n\n")
            .addJavadoc(
                "@param $L the reader that this packet will be deserialized from",
                eoReaderParameter)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(getPacketFamilyTypeName(), packetFamilyParameter)
            .addParameter(getPacketActionTypeName(), packetActionParameter)
            .addParameter(ClassName.get("dev.cirras.data", "EoReader"), eoReaderParameter)
            .addCode(deserializeSwitchBlock.build())
            .addStatement(
                "throw new $T(\"Cannot deserialize Family: \" + $L + \" Action: \" + $L)",
                IllegalArgumentException.class,
                packetFamilyParameter,
                packetActionParameter)
            .returns(packetInterfaceName)
            .build());
  }

  private static String makePacketSuffix(String packageName) {
    switch (packageName) {
      case "dev.cirras.protocol.net.client":
        return "ClientPacket";
      case "dev.cirras.protocol.net.server":
        return "ServerPacket";
      default:
        throw new CodeGenerationError(
            "Cannot create packet name suffix for package " + packageName);
    }
  }
}
