// © DEZGRE EIRL. Desarrollado por Nick Bryan.
import Foundation
import AVFoundation
import CryptoKit

private final class SoundSessionDelegate:NSObject,URLSessionTaskDelegate {
    func urlSession(_ session:URLSession,task:URLSessionTask,willPerformHTTPRedirection response:HTTPURLResponse,newRequest request:URLRequest,completionHandler:@escaping (URLRequest?)->Void){completionHandler(nil)}
}

// Cache short PCM CAF files for Apple's notification sound facility. No credentials in URLs.
actor DEZGRESoundCache {
    static let shared=DEZGRESoundCache()
    private let maxBytes=4*1024*1024
    private let maxCache=100*1024*1024
    private let session:URLSession
    private var pending=Set<String>()
    init(){
        let config=URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest=10
        config.timeoutIntervalForResource=15
        session=URLSession(configuration:config,delegate:SoundSessionDelegate(),delegateQueue:nil)
    }
    private func soundName(store:String,url:String,revision:String)->String{
        let hash=SHA256.hash(data:Data((store+"\n"+url+"\n"+revision).utf8))
        return "dz-"+hash.map{String(format:"%02x",$0)}.joined()+".caf"
    }
    func prepare(store:String,url:String,revision:String)async{
        guard let source=URL(string:url),source.scheme=="https",source.host=="api.dezgre.com",source.port==nil,source.user==nil,source.password==nil,source.path.hasPrefix("/v1/notifications/sounds/\(store)/"),!revision.isEmpty else{return}
        let name=soundName(store:store,url:url,revision:revision)
        guard !pending.contains(name) else{return}
        pending.insert(name);defer{pending.remove(name)}
        let fm=FileManager.default
        guard let library=fm.urls(for:.libraryDirectory,in:.userDomainMask).first else{return}
        let dir=library.appendingPathComponent("Sounds",isDirectory:true)
        let dest=dir.appendingPathComponent(name)
        if fm.fileExists(atPath:dest.path){return}
        let tmp=fm.temporaryDirectory.appendingPathComponent(UUID().uuidString+".mp3")
        let pcm=fm.temporaryDirectory.appendingPathComponent(UUID().uuidString+".caf")
        defer{try? fm.removeItem(at:tmp);try? fm.removeItem(at:pcm)}
        do{
            var request=URLRequest(url:source);request.setValue("audio/mpeg",forHTTPHeaderField:"Accept")
            let (bytes,response)=try await session.bytes(for:request)
            guard let http=response as? HTTPURLResponse,http.statusCode==200,response.expectedContentLength<=Int64(maxBytes) else{return}
            var data=Data()
            for try await byte in bytes{guard data.count<maxBytes else{return};data.append(byte)}
            guard !data.isEmpty else{return}
            try data.write(to:tmp,options:.atomic)
            let file=try AVAudioFile(forReading:tmp)
            let rate=file.processingFormat.sampleRate
            guard rate>=8000,rate<=96000,file.processingFormat.channelCount<=2,file.length>0,Double(file.length)/rate<30,
                  file.length<=Int64(UInt32.max),
                  let buffer=AVAudioPCMBuffer(pcmFormat:file.processingFormat,frameCapacity:AVAudioFrameCount(file.length)) else{return}
            try file.read(into:buffer)
            do{
                let output=try AVAudioFile(forWriting:pcm,settings:[
                    AVFormatIDKey:kAudioFormatLinearPCM,AVSampleRateKey:rate,
                    AVNumberOfChannelsKey:Int(file.processingFormat.channelCount),
                    AVLinearPCMBitDepthKey:16,AVLinearPCMIsFloatKey:false,AVLinearPCMIsBigEndianKey:false
                ],commonFormat:file.processingFormat.commonFormat,interleaved:file.processingFormat.isInterleaved)
                try output.write(from:buffer)
            }
            try fm.createDirectory(at:dir,withIntermediateDirectories:true)
            let existing=try fm.contentsOfDirectory(at:dir,includingPropertiesForKeys:[.fileSizeKey],options:.skipsHiddenFiles)
            let total=existing.reduce(0){$0+((try? $1.resourceValues(forKeys:[.fileSizeKey]).fileSize) ?? 0)}
            let size=(try pcm.resourceValues(forKeys:[.fileSizeKey])).fileSize ?? 0
            guard size>0,total+size<=maxCache else{return}
            try fm.moveItem(at:pcm,to:dest)
        }catch{/* Keep default system sound when unsupported, unavailable or too long. */}
    }
}
