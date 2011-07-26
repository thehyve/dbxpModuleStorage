package org.dbxp.dbxpModuleStorage

import grails.converters.JSON

class UploadedFileController {

    def uploadedFileService

    def index = { }

    def uploadFinished = {

        // assume files are stored in '/tmp' for now
        def uploadedFile = uploadedFileService.handleUploadedFileWithPath("/tmp/$params.fileName")

        // render info about stored uploaded file
        render([fileName: uploadedFile.fileName, fileSize: uploadedFile.fileSize, fileId: uploadedFile.id] as JSON)

    }
}
