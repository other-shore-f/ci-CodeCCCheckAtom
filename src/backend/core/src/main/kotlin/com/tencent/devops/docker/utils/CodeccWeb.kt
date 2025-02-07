package com.tencent.devops.docker.utils

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.tencent.bk.devops.atom.api.BaseApi
import com.tencent.bk.devops.plugin.utils.JsonUtil
import com.tencent.bk.devops.plugin.utils.OkhttpUtils
import com.tencent.devops.docker.pojo.*
import com.tencent.devops.docker.tools.FileUtil
import com.tencent.devops.docker.tools.LogUtils
import com.tencent.devops.pojo.exception.CodeccDependentException
import com.tencent.devops.pojo.exception.CodeccTaskExecException
import com.tencent.devops.pojo.exception.CodeccUserConfigException
import com.tencent.devops.utils.CodeccParamsHelper
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import org.apache.commons.codec.digest.DigestUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.BufferedOutputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object CodeccWeb : BaseApi() {

    private val jsonMediaType = "application/json".toMediaTypeOrNull()

    fun downloadAnyUnzip(commandParam: CommandParam): String {
        val workspace = commandParam.projectBuildPath
        val temp = "$workspace/.temp"
        val binFolder = "$temp/codecc_scan"

        if (File(binFolder).exists()) {
            File(binFolder).delete()
        }

        val zipPath = if (commandParam.dataRootPath.isNotBlank()) {
            LogUtils.printDebugLog("DATA_ROOT_PATH is ${commandParam.dataRootPath}")
            commandParam.dataRootPath + File.separator + "codecc_scan.zip"
        } else {
            "$workspace/.temp/codecc_scan.zip"
        }
        LogUtils.printDebugLog("zipPath is $zipPath")

        var isDownload = true
        val zipFile = File(zipPath)
        if (zipFile.exists() && zipFile.isFile) {
            val offlineMd5 = getMd5(zipFile)
            if (compareFileMd5(zipPath, "codecc_scan_external_prod.zip", "BUILD_SCRIPT", offlineMd5, commandParam.landunParam)) {
                isDownload = false
            }
        }

        // #download scan script...
        if (isDownload) {
            println("Download CodeCC scan script...")
            if (!download(zipPath, "codecc_scan_external_prod.zip", "BUILD_SCRIPT", commandParam.landunParam)) {
                println("the codecc_scan.zip download failed! please contact the CodeCC")
                throw CodeccDependentException("the codecc_scan.zip download failed! please contact the CodeCC")
            }
        }

        // #unzip codecc_scan.zip...
        LogUtils.printLog("unzip codecc_scan folder...")
        FileUtil.unzipFile(zipPath, temp)

        LogUtils.printDebugLog("binFolder is $binFolder")
        return binFolder
    }

    fun downloadCompileTool(toolName: String, toolSourceZip: String, toolBinaryName: String, suffix: String) : String{
        var fileOut: FileOutputStream?
        var conn: HttpURLConnection?
        var inputStream: InputStream?
        var rootPath = "/data/codecc_software"
        var url = "https://hub.fastgit.org/TencentBlueKing/codeccScan/raw/master/${toolName}_scan/$toolSourceZip"
        val toolHome = "$rootPath/${toolName}_scan"
        if (File(toolHome).exists() && File(toolHome).list()?.isNotEmpty() == true) {
            if (File("$toolHome/$toolBinaryName").exists()) {
                return "$toolHome/$toolBinaryName"
            }
        }
        if (!File(rootPath).exists()) {
            File(rootPath).mkdirs()
        }
        try {
            LogUtils.printLog("start to download tool zip")
            val httpUrl = URL(url)
            conn = httpUrl.openConnection() as HttpURLConnection
            conn.setRequestMethod("GET")
            conn.setDoInput(true)
            conn.setDoOutput(true)
            conn.setUseCaches(false)
            conn.connect()
            inputStream = conn.getInputStream()
            val bis = BufferedInputStream(inputStream)
            val toolFullPath = rootPath + File.separator + toolSourceZip
            fileOut = FileOutputStream(toolFullPath)
            val bos = BufferedOutputStream(fileOut)
            val buf = ByteArray(4096)
            var length = bis.read(buf)
            //保存文件
            while (length != -1) {
                bos.write(buf, 0, length)
                length = bis.read(buf)
            }
            bos.close()
            bis.close()
            conn.disconnect()

            LogUtils.printLog("start to unzip tool and tool source zip")
            FileUtil.unzipFile(toolFullPath, toolHome)
            if (toolBinaryName != ""){
                if ("tar.gz" == suffix) {
                    FileUtil.unzipTgzFile("$toolHome/$toolBinaryName.$suffix", toolHome)
                } else if ("zip" == suffix) {
                    FileUtil.unzipFile("$toolHome/$toolBinaryName.$suffix", toolHome)
                } else if ("tar.xz" == suffix) {
                    FileUtil.unzipTxzFile("$toolHome/$toolBinaryName.$suffix", toolHome)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw CodeccDependentException("get the download file $toolName failed! please check it!: ${e.message}")
        }
        LogUtils.printLog("download tool zip done.")
        return if (File("$toolHome/$toolBinaryName").exists()) {
            return "$toolHome/$toolBinaryName"
        } else {
            return "$toolHome"
        }
    }

    fun download(filePath: String, resultName: String, downloadType: String, landunParam: LandunParam): Boolean {
        var size = 0L
        val headers = getHeader(landunParam)
        val downloadUrl = "${CodeccConfig.getServerHost()}/ms/schedule/api/build/fs/download/fileSize"
        val params = mapOf(
            "fileName" to resultName,
            "downloadType" to downloadType
        )
        val requestBody = jacksonObjectMapper().writeValueAsString(params)
        val data = sendPostRequest(downloadUrl, headers, requestBody)
        try {
            val dataObj = jacksonObjectMapper().readValue<Map<String, Any?>>(data)
            if (dataObj["status"] as Int != 0) {
                LogUtils.printErrorLog(data)
                throw CodeccDependentException("get the download file $resultName size failed! please contact The CodeCC to check it!")
            } else {
                size = if (dataObj["data"] is Long) dataObj["data"] as Long else (dataObj["data"] as Int).toLong()
            }
            LogUtils.printDebugLog("size is $size")
            if (size > 0) {
                val param = mutableMapOf<String, Any?>(
                    "fileName" to resultName,
                    "downloadType" to downloadType
                )
                println("Downloading $resultName Size is ${size / (1024 * 1024)}M")
                var retainSize = size
                var label = 0L
                var sendBuffer = 0L
                var isStop = false
                val btyesBuffer = 100 * 1024 * 1024L

                val url = "${CodeccConfig.getServerHost()}/ms/schedule/api/build/fs/download"
                while (true) {
                    if (retainSize > btyesBuffer) {
                        retainSize -= btyesBuffer
                        sendBuffer = btyesBuffer
                        isStop = false
                    } else {
                        sendBuffer = retainSize
                        isStop = true
                    }
                    val a = "#" + (label * 100 / size) + " " + (100 - label * 100 / size) + "[" + (label * 100 / size) + "%" + "]"
                    println(a)
                    println("label: $label")
                    println("sendBuffer: $sendBuffer")

                    param["beginIndex"] = label.toString()
                    param["btyeSize"] = sendBuffer.toString()

                    val httpReq = Request.Builder()
                        .url(url)
                        .headers(headers.toHeaders())
                        .post(RequestBody.create(jsonMediaType, jacksonObjectMapper().writeValueAsString(param)))
                        .build()
                    OkhttpUtils.doHttp(httpReq).use { resp ->
                        if (label == 0L) {
                            FileOutputStream(filePath).use { fos ->
                                fos.write(resp.body!!.bytes())
                            }
                        } else {
                            FileOutputStream(filePath, true).use { fos ->
                                fos.write(resp.body!!.bytes())
                            }
                        }
                    }
                    label += sendBuffer
                    if (isStop) {
                        val a = "#" + label * 100 / size + " " + (100 - label * 100 / size) + "[" + (label * 100 / size) + "%" + "]"
                        println(a)
                        println("download successful!")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            LogUtils.printErrorLog("Download exception: ")
            LogUtils.printErrorLog(e.message)
            LogUtils.printErrorLog(e.cause)
            throw CodeccDependentException("get the download file $resultName size failed! please contact The CodeCC to check it!")
        }
        return false
    }

    private fun compareFileMd5(filePath: String, resultName: String, downloadType: String, offlineMd5: String, landunParam: LandunParam): Boolean {
        val headers = getHeader(landunParam)
        try {
            val param = mutableMapOf(
                "fileName" to resultName,
                "downloadType" to downloadType
            )
            val url = "${CodeccConfig.getServerHost()}/ms/schedule/api/build/fs/download/fileInfo"
            val body = jacksonObjectMapper().writeValueAsString(param)
            val responseStr = sendPostRequest(url, headers, body)
            val data = jacksonObjectMapper().readValue<Map<String, Any?>>(responseStr)
            if (data["status"] as Int != 0) {
                throw CodeccDependentException("get the download file $resultName fileInfo failed! please contact The CodeCC to check it!")
            } else {
                val fileInfo = data["data"] as Map<String, Any?>
                if (fileInfo["contentMd5"] != null) {
                    val onlineMd5 = fileInfo["contentMd5"] as String
                    if (onlineMd5 == offlineMd5) {
                        return true
                    }
                }
            }
            return false
        } catch (e: Exception) {
            print("get the download file $resultName fileInfo exception: ")
            println(e.message)
            println(e.cause)
            return false
        }
    }

    private fun getMd5(zipFile: File): String {
        zipFile.inputStream().use {
            return DigestUtils.md5Hex(it)
        }
    }

    fun codeccConfigByStream(landunParam: LandunParam, streamName: String): String {
        val headers = getHeader(landunParam)
        val path = "${CodeccConfig.getServerHost()}/ms/task/api/build/task/streamName/$streamName"
        return sendGetRequest(path, headers)
    }

    fun codeccTaskInfoByTaskId(landunParam: LandunParam, taskId: Long): String {
        val headers = getHeader(landunParam)
        val path = "${CodeccConfig.getServerHost()}/ms/task/api/build/task/taskId/$taskId"
        return sendGetRequest(path, headers)
    }

    fun getMD5ForBlame(landunParam: LandunParam, taskId: Long, tool: String): String {
        val headers = getHeader(landunParam)
        val path = "${CodeccConfig.getServerHost()}/ms/report/api/build/scm/file/list?taskId=$taskId&toolName=$tool"
        return sendGetRequest(path, headers)
    }

    fun codeccGitProjectInfoByProjectId(landunParam: LandunParam, gitProjectId: Long): String {
        val headers = getHeader(landunParam)
        val path = "${CodeccConfig.getServerHost()}/ms/task/api/build/openScan/stat/proj/$gitProjectId"
        return sendGetRequest(path, headers)
    }

    fun notifyCodeccFinish(
        landunParam: LandunParam,
        streamName: String,
        toolName: String
    ) {
        val headers = getHeader(landunParam)
        val buildId = landunParam.buildId
        val url = "${CodeccConfig.getServerHost()}/ms/report/api/build/parse/notify/streamName/$streamName/toolName/${toolName.toUpperCase()}/buildId/$buildId"
        val body = jacksonObjectMapper().writeValueAsString(mapOf<String, String>())
        LogUtils.printDebugLog("notifyCodeccFinish request url: $url")
        LogUtils.printDebugLog("notifyCodeccFinish request body: $body")
        val responseStr = sendPostRequest(url, headers, body)
        LogUtils.printDebugLog("notifyCodeccFinish response: $responseStr")
    }

    fun reportCodeccStatus(
        landunParam: LandunParam,
        streamName: String,
        toolName: String
    ): Boolean {
        val headers = getHeader(landunParam)
        val buildId = landunParam.buildId
        val url = "${CodeccConfig.getServerHost()}/ms/report/api/build/parse/reportStatus/streamName/$streamName/toolName/${toolName.toUpperCase()}/buildId/$buildId"
        val responseMap = JsonUtil.getObjectMapper().readValue<Map<String, Any>>(sendGetRequest(url, headers))
        if (responseMap["data"] == "PROCESSING") return false
        return true
    }

    fun upload(
        landunParam: LandunParam,
        filePath: String,
        resultName: String,
        uploadType: String,
        toolName: String
    ) {
        val headers = getHeader(landunParam)
        val uploadUrl = "${CodeccConfig.getServerHost()}/ms/schedule/api/build/fs/upload"

        val file = File(filePath)

        if (!file.exists()) {
            throw CodeccUserConfigException("upload file not exist: ${file.canonicalPath}")
        }

        val fis = FileInputStream(file)
        val bytesBuf = ByteArray(10 * 1024 * 1024)
        var retainSize = file.length()
        LogUtils.printDebugLog("retainSize: $retainSize")
        var chunk = 0
        val chunks = if (file.length() % bytesBuf.size == 0L) {
            file.length() / bytesBuf.size
        } else {
            file.length() / bytesBuf.size + 1
        }
        LogUtils.printDebugLog("upload $resultName size is: ${file.length()}")
        LogUtils.printDebugLog("upload url: $uploadUrl")

        while (true) {
            chunk++
            var fileBody: RequestBody
            if (retainSize > bytesBuf.size) {
                val size = fis.read(bytesBuf)
                retainSize -= size
                fileBody = RequestBody.create("multipart/form-data".toMediaTypeOrNull(), bytesBuf)
            } else {
                val lastBuffer = ByteArray(retainSize.toInt())
                val size = fis.read(lastBuffer)
                retainSize -= size
                fileBody = RequestBody.create("multipart/form-data".toMediaTypeOrNull(), lastBuffer)
            }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", resultName, fileBody)
                .addFormDataPart("fileName", resultName)
                .addFormDataPart("buildId", landunParam.buildId)
                .addFormDataPart("uploadType", uploadType)
                .addFormDataPart("chunks", if (chunks > 1) {
                    chunks.toString()
                } else "")
                .addFormDataPart("chunk", if (chunks > 1) {
                    (chunk).toString()
                } else "")
                .build()
            LogUtils.printDebugLog("upload $resultName retainSize is: $retainSize")
            LogUtils.printDebugLog("Form Data--------------")
            LogUtils.printDebugLog("file: $resultName")
            LogUtils.printDebugLog("fileName: $resultName")
            LogUtils.printDebugLog("uploadType: $uploadType")
            LogUtils.printDebugLog("chunks: " + if (chunks > 1) {
                chunks.toString()
            } else "")
            LogUtils.printDebugLog("chunk: " + if (chunks > 1) {
                (chunk).toString()
            } else "")
            LogUtils.printDebugLog("Form Data--------------")
            val request = Request.Builder()
                .url(uploadUrl)
                .headers(headers.toHeaders())
                .post(body)
                .build()
            OkhttpUtils.doHttp(request).use {
                val responseData = it.body!!.string()
                val map = JsonUtil.to(responseData, object : TypeReference<Map<String, Any>>() {})
                if (map["status"] != 0) {
                    throw CodeccDependentException(errorMsg = "upload CodeCC file fail: $responseData", toolName = toolName)
                }
                LogUtils.printDebugLog("do CodeCC file uploading: $responseData")
            }
            if (retainSize <= 0) break
        }

        if (chunks > 1) {
            val mergeParams = mapOf(
                "fileName" to resultName,
                "buildId" to landunParam.buildId,
                "uploadType" to uploadType,
                "chunks" to chunks.toString()
            )
            val mergeJson = JsonUtil.toJson(mergeParams)
            val mergeUrl = "${CodeccConfig.getServerHost()}/ms/schedule/api/build/fs/merge"
            val mergeRequest = Request.Builder()
                .url(mergeUrl)
                .headers(headers.toHeaders())
                .post(RequestBody.create("application/json".toMediaTypeOrNull(), mergeJson))
                .build()
            LogUtils.printDebugLog("merge url: $mergeUrl")
            LogUtils.printDebugLog("merge json: $mergeJson")
            OkhttpUtils.doHttpNoRetry(mergeRequest).use {
                val responseData = it.body!!.string()
                val map = JsonUtil.to(responseData, object : TypeReference<Map<String, Any>>() {})
                if (map["status"] != 0) {
                    throw CodeccDependentException("do CodeCC file merge fail: $mergeParams, $responseData")
                }
                LogUtils.printDebugLog("do CodeCC file merge sucess: $responseData")
            }
        }

        getFilePathFromServer(landunParam, resultName, uploadType)
    }

    fun getFilePathFromServer(
        landunParam: LandunParam,
        fileName: String,
        storgeType: String
    ) {
        try {
            val headers = getHeader(landunParam)
            val path = "${CodeccConfig.getServerHost()}/ms/schedule/api/build/fs/index/$storgeType/$fileName"
            val responseBody = sendGetRequest(path, headers)

            val responseResult: Result<FileIndex> = jacksonObjectMapper().readValue(responseBody)
            if (responseResult.code == "0" && responseResult.data != null) {
                val zipFilePath = responseResult.data.fileFolder + "/" + responseResult.data.fileName
                LogUtils.printLog("upload to server path : $zipFilePath")
            }
        } catch (e: Throwable) {
            LogUtils.printLog("Get file path from server failed, e: ${e.message}")
        }
    }

    fun uploadRepoInfo(
        landunParam: LandunParam,
        params: MutableMap<String, Any?>
    ) {
        val headers = getHeader(landunParam)
        val requestBody = jacksonObjectMapper().writeValueAsString(params)
        val path = "${CodeccConfig.getServerHost()}/ms/report/api/build/defects/repositories"
        LogUtils.printDebugLog("request url: $path")
        LogUtils.printDebugLog("request body: $requestBody")
        val responseContent = sendPostRequest(path, headers, requestBody, false)
        LogUtils.printDebugLog("response body: $responseContent")
    }

    fun getConfigDataByCodecc(
        streamName: String,
        toolName: String,
        commandParam: CommandParam
    ): AnalyzeConfigInfo {
        try {
            val path = "${CodeccConfig.getServerHost()}/ms/task/api/build/tool/config/streamName/$streamName/toolType/${toolName.toUpperCase()}"
            val headers = getHeader(commandParam.landunParam)
            LogUtils.printDebugLog("headerParam is: $headers")
            val scmInfoFile = commandParam.dataRootPath + File.separator + "scm_info_output.json"
            if (File(scmInfoFile).exists()){
                val outPutFileText = File(scmInfoFile).readText()
                val outPutFileObj = jacksonObjectMapper().readValue<List<Map<String, Any?>>>(outPutFileText)
                val body = mapOf(
                    "codeRepos" to outPutFileObj,
                    "repoWhiteList" to commandParam.subCodePathList
                )
                val jsonBody = jacksonObjectMapper().writeValueAsString(body)
                LogUtils.printLog("get codecc config request is: $jsonBody")
                val response = sendPostRequest(
                    url = path,
                    headers = headers,
                    requestBody = jsonBody
                )
                LogUtils.printLog("get codecc config response is: $response")
                val responseResult: Result<AnalyzeConfigInfo> = jacksonObjectMapper().readValue(response)
                if (responseResult.code == "0" && responseResult.data != null) {
                    LogUtils.printDebugLog(responseResult)
                    return responseResult.data
                } else {
                    throw CodeccDependentException("request CodeCC failed, response code: ${responseResult.code}, msg: ${responseResult.message}")
                }
            }else{
                val repoIds = if (commandParam.repoUrlMap.isNotBlank()) {
                    CodeccParamsHelper.transferStrToMap(commandParam.repoUrlMap).keys
                } else {
                    emptySet()
                }
                val body = mapOf(
                    "repoIds" to repoIds,
                    "repoWhiteList" to commandParam.subCodePathList
                )
                val jsonBody = jacksonObjectMapper().writeValueAsString(body)
                val response = sendPostRequest(
                    url = path,
                    headers = headers,
                    requestBody = jsonBody
                )
                LogUtils.printDebugLog("response is: $response")
                val responseResult: Result<AnalyzeConfigInfo> = jacksonObjectMapper().readValue(response)
                if (responseResult.code == "0" && responseResult.data != null) {
                    LogUtils.printDebugLog(responseResult)
                    return responseResult.data
                } else {
                    throw CodeccDependentException(errorMsg = "request CodeCC failed, response code: ${responseResult.code}, msg: ${responseResult.message}", toolName = toolName)
                }
            }

        } catch (e: Exception) {
            throw CodeccDependentException(errorMsg = "request CodeCC failed: ${e.message}", toolName = toolName)
        }
    }

    fun codeccGetData(
        landunParam: LandunParam,
        taskId: Long,
        toolName: String
    ): TaskLogVO? {
        val header = getHeader(landunParam)
        val url = "${CodeccConfig.getServerHost()}/ms/report/api/build/tasklog/taskId/${taskId}/toolName/$toolName/buildId/${landunParam.buildId}"
        val responseBody = sendGetRequest(url, header)
        val responseResult: Result<TaskLogVO> = jacksonObjectMapper().readValue(responseBody)
        return responseResult.data
    }

    fun codeccUploadTaskLog(
        taskId: Long,
        streamName: String,
        toolName: String,
        landunParam: LandunParam,
        stepNum: Int,
        flag: Int
    ): String {
        val headers = getHeader(landunParam)
        val requestBody = getUploadTaskLogReqParam(taskId, streamName, toolName, landunParam, stepNum, flag)
        val path = "${CodeccConfig.getServerHost()}/ms/report/api/build/tasklog"

        var countFailed = 0
        while (true) {
            if (countFailed > 3) {
                throw CodeccDependentException(errorMsg = "Request CodeCC failed 3 times, exit with exception", toolName = toolName)
            }
            try {
                val responseContent = sendPostRequest(path, headers, requestBody, false)
                LogUtils.printDebugLog("response body: $responseContent")
                val responseData: Map<String, String> = jacksonObjectMapper().readValue(responseContent)
                if (null != responseData["res"] && responseData["res"] == "2005") {
                    throw CodeccTaskExecException(errorMsg = "this job is already killed by latest job!", toolName = toolName)
                }
                return responseContent
            } catch (e: IOException) {
                LogUtils.printLog("Request CodeCC UploadTaskLog exception: ${e.message}, requestBody: $requestBody")
                countFailed++
            }
        }
    }

    fun changScanType(
        landunParam: LandunParam,
        taskId: Long,
        streamName: String,
        toolName: String
    ) {
        val headers = getHeader(landunParam)
        val requestBody = getChangeScanTypeReqParam(taskId, streamName, toolName, landunParam)
        val path = "${CodeccConfig.getServerHost()}/ms/report/api/build/toolBuildInfo/tasks/$taskId/forceFullScanSymbol"
        val responseContent = sendPostRequest(path, headers, requestBody)
        LogUtils.printDebugLog("response body: $responseContent")
    }

    private fun getChangeScanTypeReqParam(taskId: Long, streamName: String, toolName: String, landunParam: LandunParam) = jacksonObjectMapper().writeValueAsString(getRootParam(taskId, streamName, toolName, landunParam))

    private fun getRootParam(
        taskId: Long,
        streamName: String,
        toolName: String,
        landunParam: LandunParam
    ): MutableMap<String, Any> {
        return mutableMapOf(
            "stream_name" to streamName,
            "toolName" to toolName,
            "toolNames" to mutableListOf<String>(toolName.toUpperCase()),
            "landunBuildId" to landunParam.buildId,
            "pipelineBuildId" to landunParam.buildId,
            "task_id" to taskId
        )
    }

    private fun getUploadTaskLogReqParam(
        taskId: Long,
        streamName: String,
        toolName: String,
        landunParam: LandunParam,
        stepNum: Int,
        flag: Int   // 1 开始， 3 结束
    ): String {
        val rootParam = getRootParam(taskId, streamName, toolName, landunParam)

        val param = rootParam.plus(
            mutableMapOf(
                "stepNum" to stepNum.toString(),
                "startTime" to (if (flag == 1) {
                    "0"
                } else {
                    "${System.currentTimeMillis() / 1000}"
                }),
                "endTime" to (if (flag == 1) {
                    "${System.currentTimeMillis() / 1000}"
                } else {
                    "0"
                }),
                "msg" to "",
                "flag" to flag
            )
        )

        return jacksonObjectMapper().writeValueAsString(param)
    }

    private fun getHeader(landunParam: LandunParam): MutableMap<String, String> {
        return mutableMapOf(
            "Content-type" to "application/json",
            "x-devops-build-id" to landunParam.buildId,
            "x-devops-vm-sid" to landunParam.devopsAgentVmSid,
            "x-devops-project-id" to landunParam.devopsProjectId,
            "x-devops-build-type" to landunParam.devopsBuildType,
            "x-devops-agent-id" to landunParam.devopsAgentId,
            "x-devops-agent-secret-key" to landunParam.devopsAgentSecretKey
        )
    }

    private fun sendPostRequest(url: String, headers: Map<String, String>, requestBody: String, shortHttp: Boolean = true): String {
        try {
            LogUtils.printDebugLog("request url: $url")
            LogUtils.printDebugLog("request body: $requestBody")
            val httpReq = Request.Builder()
                .url(url)
                .headers(headers.toHeaders())
                .post(RequestBody.create(jsonMediaType, requestBody))
                .build()
            if (shortHttp) {
                OkhttpUtils.doShortHttp(httpReq).use { resp ->
                    val responseStr = resp.body!!.string()
                    LogUtils.printDebugLog("response body: $responseStr")
                    return responseStr
                }
            } else {
                OkhttpUtils.doHttp(httpReq).use { resp ->
                    val responseStr = resp.body!!.string()
                    LogUtils.printDebugLog("response body: $responseStr")
                    return responseStr
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.printDebugLog(e.message)
            LogUtils.printDebugLog(e.cause)
            LogUtils.printDebugLog(e.toString())
        }
        return ""
    }

    private fun sendGetRequest(url: String, headers: Map<String, String>): String {
        var countFailed = 0
        while (true) {
            if (countFailed > 3) {
                println("Request CodeCC failed 3 times, exit with exception")
                throw CodeccDependentException("Request CodeCC failed 3 times, exit with exception")
            }
            try {
                LogUtils.printDebugLog("request url: $url")
                val httpReq = Request.Builder()
                    .url(url)
                    .headers(headers.toHeaders())
                    .get()
                    .build()
                OkhttpUtils.doShortHttp(httpReq).use { resp ->
                    val responseStr = resp.body!!.string()
                    LogUtils.printDebugLog("response body: $responseStr")
                    //非200状态位都重试
                    if (!resp.isSuccessful) {
                        throw CodeccDependentException("request http request fail")
                    }
                    return responseStr
                }
            } catch (e: IOException) {
                println("Request CodeCC exception: $url, ${e.message}")
                countFailed++
            }
        }
    }

    fun getSpecConfig(landunParam: LandunParam, taskId: Long): ToolConfigPlatformVO {
        val header = getHeader(landunParam)
        val url = "${CodeccConfig.getServerHost()}/ms/task/api/build/task/toolConfig/info?taskId=$taskId&toolName=COVERITY"
        val responseBody = sendGetRequest(url, header)

        val responseResult: Result<ToolConfigPlatformVO> = jacksonObjectMapper().readValue(responseBody)
        if (responseResult.code == "0" && responseResult.data != null) {
            return responseResult.data
        } else {
            throw CodeccDependentException("request CodeCC failed, response code: ${responseResult.code}, msg: ${responseResult.message}")
        }
    }

    fun codeccStreamPush(
        landunParam: LandunParam,
        streamName: String,
        toolName: String,
        openSource : Boolean?
    ): Boolean {
        val header = getHeader(landunParam)
        val url = if(toolName == ToolConstants.COVERITY && null != openSource && openSource){
            "${CodeccConfig.getServerHost()}/ms/schedule/api/build/push/streamName/$streamName/toolName/${toolName.toUpperCase()}/buildId/${landunParam.buildId}?createFrom=gongfeng_scan"
        } else {
            "${CodeccConfig.getServerHost()}/ms/schedule/api/build/push/streamName/$streamName/toolName/${toolName.toUpperCase()}/buildId/${landunParam.buildId}"
        }
        val responseBody = sendGetRequest(url, header)
        val responseResult: Result<Boolean> = jacksonObjectMapper().readValue(responseBody)
        return responseResult.data ?: false
    }

    fun getBuildToolMeta(
        landunParam: LandunParam,
        serverHost: String
    ): List<ToolMetaDetailVO>? {
        return try {
            val header = getHeader(landunParam)
            val url = "${serverHost}/ms/task/api/build/toolmeta/list"
            val responseBody = sendGetRequest(url, header)
            val responseResult: Result<List<ToolMetaDetailVO>?> = jacksonObjectMapper().readValue(responseBody)
            responseResult.data
        } catch (e: Throwable) {
            LogUtils.printLog("get tool meta failed, use local config.: ${e.message}")
            null
        }
    }
}
