#import "BetterPlayerGumletAssetsLoaderDelegate.h"

@implementation BetterPlayerGumletAssetsLoaderDelegate

- (instancetype)init:(NSURL *)certificateURL withLicenseURL:(NSURL *)licenseURL {
    self = [super init];
    _certificateURL = certificateURL;
    _licenseURL = licenseURL;
    return self;
}

- (NSData *)getAppCertificate {
    return [NSData dataWithContentsOfURL:_certificateURL];
}

- (void)finishOnce:(AVAssetResourceLoadingRequest *)loadingRequest
           ckcData:(NSData *)ckcData
             error:(NSError *)error {

    // Prevent finishing same request twice
    static NSMutableSet < NSValue * > *finished = nil;
    static dispatch_queue_t lockQueue;
    static dispatch_once_t onceToken;

    dispatch_once(&onceToken, ^{
        finished = [NSMutableSet new];
        lockQueue = dispatch_queue_create("betterplayer.gumlet.finishonce", DISPATCH_QUEUE_SERIAL);
    });

    __block BOOL alreadyFinished = NO;
    NSValue *key = [NSValue valueWithNonretainedObject:loadingRequest];

    dispatch_sync(lockQueue, ^{
        alreadyFinished = [finished containsObject:key];
        if (!alreadyFinished) {
            [finished addObject:key];
        }
    });

    if (alreadyFinished) return;

    if (ckcData) {
        [loadingRequest.dataRequest respondWithData:ckcData];
        [loadingRequest finishLoading];
    } else {
        [loadingRequest finishLoadingWithError:error];
    }

    // cleanup later (avoid memory growth)
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(30 * NSEC_PER_SEC)),
                   dispatch_get_main_queue(), ^{
                dispatch_async(lockQueue, ^{
                    [finished removeObject:key];
                });
            });
}

- (BOOL)                 resourceLoader:(AVAssetResourceLoader *)resourceLoader
shouldWaitForLoadingOfRequestedResource:(AVAssetResourceLoadingRequest *)loadingRequest {

    NSURL *assetURI = loadingRequest.request.URL;
    NSString *str = assetURI.absoluteString;

    if (![[assetURI scheme] isEqualToString:@"skd"]) {
        return NO;
    }

    NSData *certificate = [self getAppCertificate];
    if (!certificate) {
        [loadingRequest finishLoadingWithError:[NSError errorWithDomain:NSURLErrorDomain
                                                                   code:NSURLErrorClientCertificateRejected
                                                               userInfo:@{
                                                                       NSLocalizedDescriptionKey: @"Unable to load FairPlay certificate"}]];
        return YES;
    }

    // Build SPC
    NSError *spcErr = nil;
    NSData *spcData = [loadingRequest streamingContentKeyRequestDataForApp:certificate
                                                         contentIdentifier:[str dataUsingEncoding:NSUTF8StringEncoding]
                                                                   options:nil
                                                                     error:&spcErr];

    if (!spcData || spcErr) {
        [loadingRequest finishLoadingWithError:spcErr
                                               ?: [NSError errorWithDomain:@"BetterPlayerGumletFPS"
                                                                      code:-2
                                                                  userInfo:@{
                                                                          NSLocalizedDescriptionKey: @"Failed to generate SPC"}]];
        return YES;
    }

// Call Gumlet license server ASYNC.
// It will finish the loadingRequest via finishOnce(...)
    [self requestCkcFromGumletWithSpc:spcData loadingRequest:loadingRequest];
    return YES;
}

- (BOOL)                 resourceLoader:(AVAssetResourceLoader *)resourceLoader
shouldWaitForRenewalOfRequestedResource:(AVAssetResourceRenewalRequest *)renewalRequest {
    return [self resourceLoader:resourceLoader shouldWaitForLoadingOfRequestedResource:renewalRequest];
}

- (void)requestCkcFromGumletWithSpc:(NSData *)spcData
                     loadingRequest:(AVAssetResourceLoadingRequest *)loadingRequest {

    NSString *spcBase64 = [spcData base64EncodedStringWithOptions:0];
    NSDictionary *body = @{@"spc": spcBase64};

    NSError *jsonErr = nil;
    NSData *bodyData = [NSJSONSerialization dataWithJSONObject:body options:0 error:&jsonErr];
    if (!bodyData || jsonErr) {
        [self finishOnce:loadingRequest ckcData:nil error:jsonErr];
        return;
    }

    NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:_licenseURL];
    NSLog(@"Gumlet FPS: license URL = %@", _licenseURL.absoluteString);
    req.HTTPMethod = @"POST";
    req.timeoutInterval = 20;

    [req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
    [req setValue:@"application/json" forHTTPHeaderField:@"Accept"];
    req.HTTPBody = bodyData;

    NSLog(@"Gumlet FPS: license URL = %@", self.licenseURL.absoluteString);

    NSURLSessionDataTask *task =
            [[NSURLSession sharedSession] dataTaskWithRequest:req
                                            completionHandler:^(NSData *data,
                                                                NSURLResponse *response,
                                                                NSError *error) {

                                                NSHTTPURLResponse *http = (NSHTTPURLResponse *) response;
                                                NSString *ctype = http.allHeaderFields[@"Content-Type"];

                                                if (error) {
                                                    NSLog(@"Gumlet FPS: network error = %@", error);
                                                    [self finishOnce:loadingRequest ckcData:nil error:error];
                                                    return;
                                                }

                                                NSLog(@"Gumlet FPS: status=%ld content-type=%@",
                                                      (long) http.statusCode, ctype);

                                                NSString *bodyText = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
                                                if (bodyText.length > 0) {
                                                    NSString *preview = bodyText.length > 500
                                                                        ? [bodyText substringToIndex:500]
                                                                        : bodyText;
                                                    NSLog(@"Gumlet FPS: body preview: %@", preview);
                                                } else {
                                                    NSLog(@"Gumlet FPS: empty body");
                                                }

                                                if (http.statusCode < 200 ||
                                                    http.statusCode >= 300) {
                                                    NSError *statusErr = [NSError errorWithDomain:@"BetterPlayerGumletFPS"
                                                                                             code:http.statusCode
                                                                                         userInfo:@{
                                                                                                 NSLocalizedDescriptionKey:
                                                                                                 [NSString stringWithFormat:@"License server returned %ld", (long) http.statusCode]}];
                                                    [self finishOnce:loadingRequest ckcData:nil error:statusErr];
                                                    return;
                                                }

                                                // Parse JSON: expected { "ckc": "<base64>" }
                                                NSError *parseErr = nil;
                                                id obj = [NSJSONSerialization JSONObjectWithData:data options:0 error:&parseErr];

                                                if (parseErr ||
                                                    ![obj isKindOfClass:[NSDictionary class]]) {
                                                    NSError *e = parseErr
                                                                 ?: [NSError errorWithDomain:@"BetterPlayerGumletFPS"
                                                                                        code:-10
                                                                                    userInfo:@{
                                                                                            NSLocalizedDescriptionKey: @"Invalid JSON from license server"}];
                                                    [self finishOnce:loadingRequest ckcData:nil error:e];
                                                    return;
                                                }

                                                NSString *ckcB64 = ((NSDictionary *) obj)[@"ckc"];
                                                if (![ckcB64 isKindOfClass:[NSString class]] ||
                                                    ckcB64.length == 0) {
                                                    // some providers use a different key; keep as fallback
                                                    ckcB64 = ((NSDictionary *) obj)[@"license"];
                                                }

                                                if (![ckcB64 isKindOfClass:[NSString class]] ||
                                                    ckcB64.length == 0) {
                                                    NSError *e = [NSError errorWithDomain:@"BetterPlayerGumletFPS"
                                                                                     code:-11
                                                                                 userInfo:@{
                                                                                         NSLocalizedDescriptionKey: @"Missing 'ckc' in license JSON"}];
                                                    [self finishOnce:loadingRequest ckcData:nil error:e];
                                                    return;
                                                }

                                                NSData *ckcData = [[NSData alloc] initWithBase64EncodedString:ckcB64 options:0];
                                                if (!ckcData) {
                                                    NSError *e = [NSError errorWithDomain:@"BetterPlayerGumletFPS"
                                                                                     code:-12
                                                                                 userInfo:@{
                                                                                         NSLocalizedDescriptionKey: @"Failed to decode CKC base64"}];
                                                    [self finishOnce:loadingRequest ckcData:nil error:e];
                                                    return;
                                                }

                                                [self finishOnce:loadingRequest ckcData:ckcData error:nil];
                                            }];

    [task resume];
}

@end