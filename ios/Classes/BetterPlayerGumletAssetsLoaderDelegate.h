#import <Foundation/Foundation.h>
#import <AVFoundation/AVFoundation.h>

@interface BetterPlayerGumletAssetsLoaderDelegate : NSObject <AVAssetResourceLoaderDelegate>

@property(readonly, nonatomic) NSURL* certificateURL;
@property(readonly, nonatomic) NSURL* licenseURL;

- (instancetype)init:(NSURL *)certificateURL withLicenseURL:(NSURL *)licenseURL;

@end