AJS.toInit(function() {
	$('#overwritten').on('change', function(event) {
		$('#default-values').prop('disabled', !event.target.checked)
		$('#test-values-directory').prop('disabled', !event.target.checked)
		$('#template-mode input').prop('disabled', !event.target.checked)
		$('#helmfile-environments').prop('disabled', !event.target.checked)
		$('#env-entries').prop('disabled', !event.target.checked)
	});
});
