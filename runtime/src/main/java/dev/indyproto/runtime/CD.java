package dev.indyproto.runtime;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;

/** Shared {@link ClassDesc} constants used by both build-time and link-time code generation. */
public final class CD {
  private CD() {}

  public static final ClassDesc I = ConstantDescs.CD_int;
  public static final ClassDesc J = ConstantDescs.CD_long;
  public static final ClassDesc F = ConstantDescs.CD_float;
  public static final ClassDesc D = ConstantDescs.CD_double;
  public static final ClassDesc Z = ConstantDescs.CD_boolean;
  public static final ClassDesc B = ConstantDescs.CD_byte;
  public static final ClassDesc V = ConstantDescs.CD_void;
  public static final ClassDesc OBJECT = ConstantDescs.CD_Object;
  public static final ClassDesc STRING = ConstantDescs.CD_String;
  public static final ClassDesc CLASS = ConstantDescs.CD_Class;
  public static final ClassDesc LIST = ClassDesc.of("java.util.List");
  public static final ClassDesc ARRAY_LIST = ClassDesc.of("java.util.ArrayList");
  public static final ClassDesc COLLECTIONS = ClassDesc.of("java.util.Collections");
  public static final ClassDesc OBJECTS = ClassDesc.of("java.util.Objects");
  public static final ClassDesc ITERABLE = ClassDesc.of("java.lang.Iterable");
  public static final ClassDesc INPUT_STREAM = ClassDesc.of("java.io.InputStream");
  public static final ClassDesc BYTE_BUFFER = ClassDesc.of("java.nio.ByteBuffer");
  public static final ClassDesc IO_EXCEPTION = ClassDesc.of("java.io.IOException");
  public static final ClassDesc FLOAT_BOX = ClassDesc.of("java.lang.Float");
  public static final ClassDesc DOUBLE_BOX = ClassDesc.of("java.lang.Double");

  private static ClassDesc pb(String n) {
    return ClassDesc.of("com.google.protobuf." + n);
  }

  public static final ClassDesc BYTE_STRING = pb("ByteString");
  public static final ClassDesc CIS = pb("CodedInputStream");
  public static final ClassDesc COS = pb("CodedOutputStream");
  public static final ClassDesc ERL = pb("ExtensionRegistryLite");
  public static final ClassDesc ER = pb("ExtensionRegistry");
  public static final ClassDesc IPBE = pb("InvalidProtocolBufferException");
  public static final ClassDesc LAZY_STRING_LIST = pb("LazyStringArrayList");
  public static final ClassDesc PROTOCOL_STRING_LIST = pb("ProtocolStringList");
  public static final ClassDesc INTERNAL = pb("Internal");
  public static final ClassDesc PROTOBUF_LIST = pb("Internal$ProtobufList");
  public static final ClassDesc INT_LIST = pb("Internal$IntList");
  public static final ClassDesc ENUM_LITE_MAP = pb("Internal$EnumLiteMap");
  public static final ClassDesc UNKNOWN_FIELD_SET = pb("UnknownFieldSet");
  public static final ClassDesc MESSAGE = pb("Message");
  public static final ClassDesc MESSAGE_BUILDER = pb("Message$Builder");
  public static final ClassDesc MESSAGE_LITE = pb("MessageLite");
  public static final ClassDesc MESSAGE_OR_BUILDER = pb("MessageOrBuilder");
  public static final ClassDesc GENERATED_MESSAGE = pb("GeneratedMessage");
  public static final ClassDesc GM_BUILDER = pb("GeneratedMessage$Builder");
  public static final ClassDesc GM_BUILDER_PARENT = pb("AbstractMessage$BuilderParent");
  public static final ClassDesc GM_FIELD_ACCESSOR_TABLE = pb("GeneratedMessage$FieldAccessorTable");
  public static final ClassDesc ABSTRACT_MESSAGE_LITE_BUILDER = pb("AbstractMessageLite$Builder");
  public static final ClassDesc GENERATED_FILE = pb("GeneratedFile");
  public static final ClassDesc PARSER = pb("Parser");
  public static final ClassDesc ABSTRACT_PARSER = pb("AbstractParser");
  public static final ClassDesc SINGLE_FIELD_BUILDER = pb("SingleFieldBuilder");
  public static final ClassDesc REPEATED_FIELD_BUILDER = pb("RepeatedFieldBuilder");
  public static final ClassDesc DESCRIPTOR = pb("Descriptors$Descriptor");
  public static final ClassDesc FILE_DESCRIPTOR = pb("Descriptors$FileDescriptor");
  public static final ClassDesc ENUM_DESCRIPTOR = pb("Descriptors$EnumDescriptor");
  public static final ClassDesc ENUM_VALUE_DESCRIPTOR = pb("Descriptors$EnumValueDescriptor");
  public static final ClassDesc PROTOCOL_MESSAGE_ENUM = pb("ProtocolMessageEnum");

  public static final ClassDesc RT = ClassDesc.of("dev.indyproto.runtime.Rt");
  public static final ClassDesc BOOTSTRAPS = ClassDesc.of("dev.indyproto.runtime.ProtoBootstraps");
}
