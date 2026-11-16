package dev.indyproto.gen;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.indyproto.runtime.CD;
import dev.indyproto.runtime.Schema;
import dev.indyproto.runtime.Schema.Kind;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.List;

/** Per-message view of the descriptor, with everything the class writers need. */
final class Model {

  /** A field plus the class names it refers to. */
  record F(Schema.Field s, FieldDescriptor fd, ClassDesc type, ClassDesc typeBuilder,
      ClassDesc typeOrBuilder, boolean openEnum) {

    String cap() { return s.camelName(); }
    String field() { return s.fieldName(); }
    String fb() { return s.builderFieldName(); }
    Kind kind() { return s.kind(); }
    boolean repeated() { return s.repeated(); }
    boolean presence() { return s.presence(); }
    ClassDesc storage() { return s.storageDesc(); }
  }

  final Descriptor d;
  final ClassDesc msg;
  final ClassDesc builder;
  final ClassDesc orBuilder;
  final List<F> fields = new ArrayList<>();
  final Schema schema;

  Model(Descriptor d, Names names) {
    this.d = d;
    this.msg = names.message(d);
    this.builder = names.builder(d);
    this.orBuilder = names.orBuilder(d);
    int bit = 0;
    List<Schema.Field> sf = new ArrayList<>();
    for (FieldDescriptor fd : d.getFields()) {
      check(fd);
      Kind kind = kindOf(fd);
      boolean repeated = fd.isRepeated();
      boolean presence = !repeated && fd.hasPresence();
      boolean closed = kind == Kind.ENUM && fd.legacyEnumFieldTreatedAsClosed();
      ClassDesc type = null, tb = null, tob = null;
      if (kind == Kind.MESSAGE) {
        type = names.message(fd.getMessageType());
        tb = names.builder(fd.getMessageType());
        tob = names.orBuilder(fd.getMessageType());
        if (names.isWellKnown(fd.getMessageType().getFile())) {
          // Well-known types use protobuf-java's own stock classes.
          type = wkt(fd.getMessageType(), names);
          tb = ClassDesc.of(Names.internal(type).replace('/', '.') + "$Builder");
          tob = ClassDesc.of(Names.internal(type).replace('/', '.') + "OrBuilder");
        }
      } else if (kind == Kind.ENUM) {
        type = names.enumClass(fd.getEnumType());
      }
      Schema.Field f = new Schema.Field(
          fd.getNumber(), kind, repeated, repeated && fd.isPacked(), presence,
          kind == Kind.STRING && fd.needsUtf8Check(), closed,
          presence ? bit++ : -1,
          Names.camel(fd.getName(), true),
          type == null ? null : Names.internal(type));
      sf.add(f);
      fields.add(new F(f, fd, type, tb, tob, kind == Kind.ENUM && !closed));
    }
    this.schema = new Schema(sf);
  }

  private static ClassDesc wkt(Descriptor d, Names names) {
    return names.message(d);
  }

  private static void check(FieldDescriptor fd) {
    String why = null;
    if (fd.isMapField()) why = "map fields";
    else if (fd.getRealContainingOneof() != null) why = "oneof";
    else if (fd.isRequired()) why = "required fields";
    else if (fd.hasDefaultValue()) why = "explicit [default = ...]";
    else if (fd.getType() == FieldDescriptor.Type.GROUP) why = "groups";
    else if (fd.isExtension()) why = "extensions";
    if (why != null) {
      throw new UnsupportedOperationException(
          "prototype does not support " + why + " (" + fd.getFullName() + ")");
    }
  }

  static Kind kindOf(FieldDescriptor fd) {
    return switch (fd.getType()) {
      case INT32 -> Kind.INT32;
      case INT64 -> Kind.INT64;
      case UINT32 -> Kind.UINT32;
      case UINT64 -> Kind.UINT64;
      case SINT32 -> Kind.SINT32;
      case SINT64 -> Kind.SINT64;
      case FIXED32 -> Kind.FIXED32;
      case FIXED64 -> Kind.FIXED64;
      case SFIXED32 -> Kind.SFIXED32;
      case SFIXED64 -> Kind.SFIXED64;
      case FLOAT -> Kind.FLOAT;
      case DOUBLE -> Kind.DOUBLE;
      case BOOL -> Kind.BOOL;
      case ENUM -> Kind.ENUM;
      case STRING -> Kind.STRING;
      case BYTES -> Kind.BYTES;
      case MESSAGE -> Kind.MESSAGE;
      case GROUP -> throw new UnsupportedOperationException("groups");
    };
  }

  /** Java type of a single element / value as seen by users (getFoo / setFoo). */
  static ClassDesc valueType(F f) {
    return switch (f.kind()) {
      case STRING -> CD.STRING;
      case BYTES -> CD.BYTE_STRING;
      case MESSAGE, ENUM -> f.type();
      default -> f.kind().valueDesc();
    };
  }

  static ClassDesc boxed(ClassDesc prim) {
    return switch (prim.descriptorString()) {
      case "I" -> ClassDesc.of("java.lang.Integer");
      case "J" -> ClassDesc.of("java.lang.Long");
      case "F" -> ClassDesc.of("java.lang.Float");
      case "D" -> ClassDesc.of("java.lang.Double");
      case "Z" -> ClassDesc.of("java.lang.Boolean");
      default -> prim;
    };
  }
}
