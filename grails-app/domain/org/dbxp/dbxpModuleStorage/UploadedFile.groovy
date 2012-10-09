package org.dbxp.dbxpModuleStorage

import com.mongodb.gridfs.GridFSDBFile
import org.dbxp.moduleBase.Assay
import org.dbxp.moduleBase.User
import org.dbxp.matriximporter.MatrixImporter

class UploadedFile {

    def uploadedFileService

    static transients = ['uploadedFileService', 'file', 'inputStream', 'byteArrayOutputStream', 'bytes']
    static mapWith = 'mongo'

    Date dateCreated
    Date lastUpdated

    User uploader // user who uploaded the file

    // This is a hex string representing a Mongo ObjectId
    String gridFSFile_id

    String fileName
    Long fileSize

    Assay assay

    // a 2D matrix containing the parsed data as strings. Matrix[0] gives first
    // row, Matrix[0][0] give first cell of that row.
    def matrix

	// index of the row containing the features
	Integer featureRowIndex = 0

	// index of the column containing the sample names
	Integer sampleColumnIndex = 0

	// size of the matrix in rows/columns
	Integer rows = 0
	Integer columns = 0

	// a list of column indices that will not be exposed to the outside (e.g.
	// via REST calls)
	Integer[] ignoredDataColumns = []

	Map parseInfo

	Boolean isColumnOriented = false

    static constraints = {
        uploader(nullable: true)
		matrix(nullable: true)
        assay(nullable: true)
		parseInfo(nullable: true)
    }

    GridFSDBFile getFile() {
        uploadedFileService.getGridFSDBFileByID(gridFSFile_id)
    }

    InputStream getInputStream() {
        file?.inputStream
    }

    ByteArrayOutputStream getByteArrayOutputStream() {
        new ByteArrayOutputStream((int) fileSize) << file?.inputStream
    }

    byte[] getBytes() {
        byteArrayOutputStream?.toByteArray()
    }

    void parse(Map hints = [:]) {
        def (newMatrix, newParseInfo) = MatrixImporter.instance.importInputStream(inputStream, hints + [fileName: fileName], true)

        if (!newMatrix) {
            throw new RuntimeException("Could not parse file $fileName. Contents are not of a type that can be read into a two-dimensional array.")
        }

        // check whether all rows have equal length
        if ( (newMatrix*.size().unique()).size > 1) {
            throw new RuntimeException("Error parsing file $fileName; every row should have the same number of columns.")
        }

		matrix = newMatrix
		rows = matrix.size
		columns = matrix[0].size
		parseInfo = newParseInfo

        // check whether dimensions are at least 2x1
        if (rows < 2 || columns < 1) {
            throw new RuntimeException("Error importing file: file should have at least two rows and 1 column. Rows: $rows columns: $columns.")
        }
    }

	void clearParsedData() {
		matrix = []
		rows = 0
		columns = 0
		parseInfo = [:]
	}

    void delete() {
        uploadedFileService.deleteUploadedFile(this)
    }

    // TODO: why not with dependency injection??
    def getUploadedFileService() {
    	if (!uploadedFileService) {
    		uploadedFileService = new UploadedFileService()
    	}
    	uploadedFileService
    }

    def getAssaySamplesWithData() {

        if (!matrix) return []

        uploadedFileService = new UploadedFileService()

        def sampleNames = uploadedFileService.getSampleNames(this)
        def samplesInAssay = Assay.get(assay?.id)?.samples
        def sampleNamesInAssay = samplesInAssay*.name

        sampleNames.intersect(sampleNamesInAssay)
    }

    def getSamplesWithData() {
        if (!matrix) return []
        uploadedFileService = new UploadedFileService()
        uploadedFileService.getSampleNames(this)
    }

    def determineAmountOfSamplesWithData() {
    	// TODO: it's very inefficient to constantly determine this on the fly
    	getSamplesWithData().size()
    }

    def getDataColumnHeaders() {

        uploadedFileService = new UploadedFileService()

        uploadedFileService.getDataColumnHeaders(this)
    }
}
