# Generate RSA private key with SHA-512 as the hash
```bash
openssl genpkey \
  -algorithm RSA \
  -pkeyopt rsa_keygen_bits:3072 \
  -out rs512-private.pem
```

## Extract public key
```bash
openssl pkey \
  -in rs512-private.pem \
  -pubout \
  -out rs512-public.pem
```

# Generate EC private key in an OpenSSL specific PEM format
```bash
openssl ecparam -name prime256v1 -genkey -noout -out ec_private_key.pem
```

## To convert it to pkcs8 PEM format
```bash
openssl pkcs8 -in ec_private_key.pem -nocrypt -topk8 -outform PEM
```

## Extract public key
```bash
openssl ec -in ec_private_key.pem -pubout -out ec_public_key.pem
```
