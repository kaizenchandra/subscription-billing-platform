package com.acme.billing.documents;
import java.nio.file.*;
import java.util.UUID;
public final class LocalDocumentStorage implements DocumentStorage {
 private final Path root;
 public LocalDocumentStorage(Path root){this.root=root.toAbsolutePath().normalize();}
 private Path path(String key){DocumentStorage.validateKey(key);var path=root.resolve(key).normalize();if(!path.startsWith(root))throw new IllegalArgumentException("Invalid document path");return path;}
 public void put(String key,byte[] bytes){
  if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("Document exceeds size limit");
  var target=path(key);Path temp=null;
  try{Files.createDirectories(target.getParent());if(Files.exists(target))return;temp=Files.createTempFile(target.getParent(),"upload-",".tmp");Files.write(temp,bytes);Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);}catch(java.io.IOException e){throw new IllegalStateException("Document storage unavailable");}finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(java.io.IOException ignored){}}
 }
 public byte[] get(String key){try(var stream=Files.newInputStream(path(key))){byte[] bytes=stream.readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw new IllegalStateException("Document exceeds size limit");return bytes;}catch(java.io.IOException e){throw new IllegalStateException("Document storage unavailable");}}
}
