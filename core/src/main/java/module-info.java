module software.sava.core {
  requires java.net.http;
  requires systems.comodal.json_iterator;
  requires org.bouncycastle.provider;

  exports software.sava.core.accounts;
  exports software.sava.core.accounts.lookup;
  exports software.sava.core.accounts.meta;
  exports software.sava.core.accounts.token;
  exports software.sava.core.borsh;
  exports software.sava.core.crypto;
  exports software.sava.core.crypto.ed25519;
  exports software.sava.core.encoding;
  exports software.sava.core.rpc;
  exports software.sava.core.tx;
  exports software.sava.core.util;
  exports software.sava.core.crypto.bip32;
}
