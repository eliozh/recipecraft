# Project-specific R8 rules for the release build.
#
# No app rules are needed today; every library ships its own consumer rules:
# - kotlinx.serialization: the runtime jar's rules keep the generated serializers and the
#   companion serializer() of @Serializable classes. The app always passes the serializer
#   explicitly (Foo.serializer()), never looks one up by reflection.
# - Room and Hilt: generated code is referenced directly; Room keeps *_Impl by its own rules.
# - Tink (including its protobuf-lite key formats) and OkHttp/Okio: their consumer rules.
#
# Add a rule here only when a release run shows it is needed, with a comment saying why.
