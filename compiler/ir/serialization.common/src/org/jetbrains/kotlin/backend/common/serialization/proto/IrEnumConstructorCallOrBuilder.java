// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: compiler/ir/serialization.common/src/KotlinIr.proto

package org.jetbrains.kotlin.backend.common.serialization.proto;

public interface IrEnumConstructorCallOrBuilder extends
    // @@protoc_insertion_point(interface_extends:org.jetbrains.kotlin.backend.common.serialization.proto.IrEnumConstructorCall)
    org.jetbrains.kotlin.protobuf.MessageLiteOrBuilder {

  /**
   * <code>required int64 symbol = 1;</code>
   */
  boolean hasSymbol();
  /**
   * <code>required int64 symbol = 1;</code>
   */
  long getSymbol();

  /**
   * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.MemberAccessCommon member_access = 2;</code>
   */
  boolean hasMemberAccess();
  /**
   * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.MemberAccessCommon member_access = 2;</code>
   */
  org.jetbrains.kotlin.backend.common.serialization.proto.MemberAccessCommon getMemberAccess();
}