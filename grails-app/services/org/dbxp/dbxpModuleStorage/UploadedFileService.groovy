package org.dbxp.dbxpModuleStorage

import com.mongodb.gridfs.GridFS
import com.mongodb.gridfs.GridFSDBFile
import com.mongodb.gridfs.GridFSInputFile
import org.bson.types.ObjectId
import org.dbxp.moduleBase.User
import org.dbxp.moduleBase.Sample

class UploadedFileService {
	static transactional = 'mongo'

	def grailsApplication
	def mongoDatastore
	def assayService

	GridFS gridFS

	/**
	 * This will be called after the service has been initialized. We're initializing the
	 * gridFS here.
	 */
	synchronized GridFS getGridFS() {
		def db

		// already got a gridFS instance?
		if (!this.gridFS) {
			// no, instantiate it
			db = mongoDatastore.mongo.getDB(mongoDatastore.mappingContext.defaultDatabaseName)
			this.gridFS = new GridFS(db)
		}

		return this.gridFS
	}

	/**
	 * import file from disk into mongodb
	 * @param path
	 * @param user
	 * @return
	 */
	UploadedFile handleUploadedFileWithPath(String path, User user) {
		File file = new File(path)
		def uploadedFile = null

		if (file.canRead()) {
			uploadedFile = createUploadedFileFromFile(file, user).save()
		}

		file.delete()

		return uploadedFile
	}

	/**
	 * Reads a file and returns an instance of UploadedFile.
	 *
	 * @param file the file to load
	 * @return an UploadedFile instance
	 */
	UploadedFile createUploadedFileFromFile(File file, User user) {
		GridFSInputFile gridFSInputFile = this.getGridFS().createFile(file)
		gridFSInputFile.save()

		def uploadedFile = new UploadedFile(
			uploader: user,
			gridFSFile_id: gridFSInputFile.id.toString(),
			fileName: file.name,
			fileSize: file.length()
		)

		try {
			uploadedFile.save(failOnError: true)
		} catch (Exception e) {
			// something is going wrong with mongodb
			throw new Exception("UploadedFileService is unable to save the uploadedFile; make sure MongoDB is running...")
//			println e.getMessage()
//			println e.getStackTrace()
		}

		return uploadedFile
	}

	GridFSDBFile getGridFSDBFileByID(String objectIdString) {
		getGridFSDBFileByID(new ObjectId(objectIdString))
	}

	GridFSDBFile getGridFSDBFileByID(ObjectId objectId) {
		this.getGridFS().findOne(objectId)
	}
	
	List getFilesUploadedByUser(user) {
		UploadedFile.findAllByUploader(user)
	}

	List getUploadedFilesFromAssaysReadableByUser(user) {
		UploadedFile.findAllByAssayInList(assayService.getAssaysReadableByUser(user))
	}

	List getUploadedFilesFromAssaysWritableByUser(user) {
		UploadedFile.findAllByAssayInList(assayService.getAssaysWritableByUser(user))
	}

	List getUploadedFilesForUser(user) {
		(getFilesUploadedByUser(user) + getUploadedFilesFromAssaysWritableByUser(user)).unique()
	}

	List getUnassignedUploadedFilesForUser(user) {
		getUploadedFilesForUser(user).findAll {!it.assay?.id}
	}

	/**
	 * Deletes uploaded file and attached grid fs file
	 * @param uploadedFile
	 * @return
	 */
	def deleteUploadedFile(UploadedFile uploadedFile) {
		this.getGridFS().remove(new ObjectId(uploadedFile.gridFSFile_id))
		uploadedFile.delete()
	}

	/**
	 * Gets data from columns with specified columnIndices. Does not include header in result.
	 *
	 * @param uploadedFile
	 * @param columnIndices
	 * @return columns * rows structured array
	 */
	ArrayList getDataFromColumns(UploadedFile uploadedFile, ArrayList columnIndices) {
		// TODO: check whether this can be more efficient
		def transposedData = uploadedFile?.matrix?.transpose()

		if (!transposedData) return []

		transposedData[columnIndices].collect { it[(uploadedFile.featureRowIndex + 1)..-1] }
	}

	/**
	 * Gets data from the column with specified columnIndex. Does not include header in result.
	 *
	 * @param uploadedFile
	 * @param columnIndex
	 * @return
	 */
	ArrayList getDataFromColumn(UploadedFile uploadedFile, Integer columnIndex) {
		getDataFromColumns(uploadedFile, [columnIndex])[0] as ArrayList
	}

	/**
	 * Returns the contents of the header row of a parsed file.
	 *
	 * @param uploadedFile
	 * @return
	 */
	ArrayList getHeaderRow(UploadedFile uploadedFile) {
		uploadedFile.matrix[uploadedFile.featureRowIndex]
	}

	/**
	 * Returns indices from columns that contain data. The sample column and indices from uploadedFile.ignoredDataColumns
	 * are omitted.
	 *
	 * @param uploadedFile
	 * @return
	 */
	ArrayList getDataColumnIndices(UploadedFile uploadedFile) {
		(0..uploadedFile.columns - 1) - uploadedFile.sampleColumnIndex - uploadedFile.ignoredDataColumns
	}

	/**
	 * Returns data column headers from the uploaded file.
	 * The sample column and columns with indices from uploadedFile.ignoredDataColumns are not returned.
	 *
	 * @param uploadedFile
	 * @return
	 */
	ArrayList getDataColumnHeaders(UploadedFile uploadedFile) {
		getHeaderRow(uploadedFile)[getDataColumnIndices(uploadedFile)]
	}

	/**
	 * Returns feature names from the header of the data matrix. Only names that exist in measurement platform version
	 * will be returned
	 *
	 * @param uploadedFile
	 * @return
	 */
	ArrayList getFeatureNames(UploadedFile uploadedFile) {

		// workaround for mongo bug ...
		def assay = org.dbxp.moduleBase.Assay.get(uploadedFile.assay?.id)

		if (!assay || !uploadedFile.matrix) return []
		
		def measurementPlatformFeatureLabels = assay.measurementPlatformVersion?.features*.feature?.label
		def headerRowLabels = getDataColumnHeaders(uploadedFile)

		measurementPlatformFeatureLabels?.intersect(headerRowLabels) ?: []
	}

	/**
	 * Returns data columns for given measurementTokens.
	 *
	 * @param UploadedFile
	 * @param measurementTokens
	 * @return
	 */
	ArrayList getDataForMeasurementTokens(UploadedFile uploadedFile, measurementTokens) {
		ArrayList columnIndices = getHeaderRow(uploadedFile).findIndexValues { it in measurementTokens }
		getDataFromColumns(uploadedFile, columnIndices)
	}

	/**
	 * Returns data from the columns that contain measurement data.
	 *
	 * @param UploadedFile
	 * @return
	 */
	ArrayList getMeasurementData(UploadedFile UploadedFile) {
		ArrayList dataColumns = getDataColumnIndices(UploadedFile)
		getDataFromColumns(UploadedFile, dataColumns)
	}

	/**
	 * Returns sample names from the parsed file.
	 * It does this by return the data for the column marked as 'sampleColumnIndex'
	 *
	 * @param UploadedFile
	 * @return
	 */
	ArrayList getSampleNames(UploadedFile uploadedFile) {
		getDataFromColumn(uploadedFile, uploadedFile?.sampleColumnIndex)
	}

	/**
	 * Retrieves row indices that belong to given sample names.
	 *
	 * @param UploadedFile
	 * @param sampleNames
	 * @return
	 */
	ArrayList getColumnIndicesForMeasurementTokens(UploadedFile uploadedFile, List measurementTokens) {
		measurementTokens.collect { token -> getHeaderRow(uploadedFile).findIndexOf { it in [token] } }
	}

	/**
	 * Retrieves row indices that belong to given sample names, searching only in real data rows.
	 *
	 * @param UploadedFile
	 * @param sampleNames
	 * @return
	 */
	ArrayList getRowIndicesForSampleNamesInData(UploadedFile uploadedFile, sampleNames) {
		// calculate indices relative to data start
		def relativeIndices = sampleNames.collect { name -> uploadedFile.matrix[(uploadedFile.featureRowIndex + 1)..-1].findIndexOf { row -> row[uploadedFile.sampleColumnIndex] in [name] }}
		// add header row count to obtain indexes that can directly be used on the data matrix
		relativeIndices*.plus(uploadedFile.featureRowIndex + 1)
	}


	/**
	 * Retrieves row indices that belong to given sample names, searching in all rows.
	 * Is here for legacy reasons only.
	 * @param UploadedFile
	 * @param sampleNames
	 * @return
	 */
	ArrayList getRowIndicesForSampleNames(UploadedFile uploadedFile, sampleNames) {
		uploadedFile.matrix.findIndexValues { row -> row[uploadedFile.sampleColumnIndex] in sampleNames }
	}

	/**
	 * Same as getRowIndicesForSampleNames but returns indices relative to data start
	 */
	ArrayList getRowIndicesForSampleNamesRelativeToDataStart(UploadedFile uploadedFile, sampleNames) {
		getRowIndicesForSampleNames(uploadedFile, sampleNames).collect { it - uploadedFile.featureRowIndex - 1 }
	}

	/**
	 * Returns data for specific samples
	 *
	 * @param UploadedFile
	 * @param sampleNames
	 * @return
	 */
	ArrayList getDataForSampleNames(UploadedFile uploadedFile, sampleNames) {
		def rowIndices = getRowIndicesForSampleNamesRelativeToDataStart(uploadedFile, sampleNames)
		getMeasurementData(uploadedFile)[rowIndices]
	}

	/**
	 * Returns data for specific samples and measurementTokens
	 *
	 * @param UploadedFile
	 * @param sampleNames
	 * @param measurementTokens
	 * @return
	 */
	ArrayList getDataForSampleTokensAndMeasurementTokens(UploadedFile uploadedFile, List sampleTokens, List measurementTokens) {
		// retrieve sample names for the given tokens and store them in the same order
		List sampleNames = sampleTokens.collect { Sample.findBySampleToken(it).name }
		// use these names to obtain row indices
		List rowIndices = getRowIndicesForSampleNamesInData(uploadedFile, sampleNames)
		// obtain column indices for the given measurement 'tokens' (names)
		List columnIndices = getColumnIndicesForMeasurementTokens(uploadedFile, measurementTokens)
		// return the data by directly indexing the data matrix
		uploadedFile?.matrix[rowIndices].collect { it[columnIndices] }
	}

	/**
	 * Transposes the matrix and adjusts rows, columns and isColumnOriented parameters
	 *
	 *
	 * @param UploadedFile
	 * @return
	 */
	UploadedFile transposeMatrix(UploadedFile uploadedFile) {
		uploadedFile.matrix = uploadedFile.matrix.transpose()
		uploadedFile.rows = uploadedFile.matrix.size
		uploadedFile.columns = uploadedFile.matrix[0].size()
		uploadedFile.isColumnOriented = !uploadedFile.isColumnOriented

		return uploadedFile
	}

	/**
	 * Returns sample count for the uploaded file as indicated by the rows property and the feature row index
	 *
	 * @param UploadedFile
	 * @return
	 */
	def sampleCount(UploadedFile uploadedFile) {
		uploadedFile.rows - uploadedFile.featureRowIndex - 1
	}
}
